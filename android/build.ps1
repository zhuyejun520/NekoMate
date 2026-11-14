# NekoMate Android build (no Gradle, relies on the local Android SDK)
$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$JAVA_HOME = "C:\Android\jdk17\jdk-17.0.21.0.2+1"
$SDK = "C:\Android\sdk"
$BT = "$SDK\build-tools\35.0.0"
$PLATFORM = "$SDK\platforms\android-35\android.jar"
$env:JAVA_HOME = $JAVA_HOME
$env:PATH = "$JAVA_HOME\bin;$BT;$env:PATH"

if (!(Test-Path $PLATFORM)) { throw "android.jar not found: $PLATFORM" }
if (!(Test-Path "$BT\aapt2.exe")) { throw "aapt2 not found: $BT" }

$build = "$root\build"
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path "$build\gen", "$build\classes", "$build\dex", "$root\dist" | Out-Null

Write-Host "[1/7] aapt2 compile"
& "$BT\aapt2.exe" compile --dir "$root\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/7] aapt2 link"
& "$BT\aapt2.exe" link `
    -o "$build\app-unsigned.apk" `
    -I $PLATFORM `
    --manifest "$root\AndroidManifest.xml" `
    --java "$build\gen" `
    --min-sdk-version 26 `
    --target-sdk-version 35 `
    --version-code 76 `
    --version-name "2.3.47" `
    "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/7] javac"
$sources = @(Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem "$build\gen" -Recurse -Filter R.java | ForEach-Object { $_.FullName })
[System.IO.File]::WriteAllLines("$build\sources.txt", $sources, (New-Object System.Text.UTF8Encoding($false)))
& "$JAVA_HOME\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $PLATFORM -d "$build\classes" "@$build\sources.txt"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] jar + d8"
& "$JAVA_HOME\bin\jar.exe" cf "$build\classes.jar" -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& "$BT\d8.bat" --min-api 26 --lib $PLATFORM --output "$build\dex" "$build\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/7] inject dex"
$python = Join-Path $root "..\.venv\Scripts\python.exe"
if (!(Test-Path $python)) { $python = "python" }
& $python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" "$build\app-unsigned.apk" "$build\dex\classes.dex"
if ($LASTEXITCODE -ne 0) { throw "dex inject failed" }

Write-Host "[6/7] zipalign"
& "$BT\zipalign.exe" -f 4 "$build\app-unsigned.apk" "$build\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[7/7] sign"
$ks = if ($env:NEKOMATE_KEYSTORE) { $env:NEKOMATE_KEYSTORE } else { "$root\keystore\nekomate.jks" }
$storePass = $env:NEKOMATE_STORE_PASS
$keyPass = $env:NEKOMATE_KEY_PASS
if (-not $storePass -or -not $keyPass) { throw "Set NEKOMATE_STORE_PASS and NEKOMATE_KEY_PASS" }
if (!(Test-Path $ks)) {
    & "$JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore $ks -alias nekomate -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $storePass -keypass $keyPass `
        -dname "CN=NekoMate, OU=Dev, O=NekoMate, L=Hangzhou, ST=Zhejiang, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
$out = "$root\dist\NekoMate-2.3.47.apk"
if (Test-Path $out) { Remove-Item -Force $out }
& "$BT\apksigner.bat" sign --ks $ks --ks-key-alias nekomate `
    --ks-pass pass:$storePass --key-pass pass:$keyPass `
    --out $out "$build\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$BT\apksigner.bat" verify --print-certs $out
Write-Host "APK: $out"
Get-Item $out | Select-Object FullName, Length
