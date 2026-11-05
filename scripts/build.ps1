param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Force -Path "$projectRoot\runtime\tmp","$projectRoot\.cache\m2" | Out-Null
$env:TEMP = "$projectRoot\runtime\tmp"
$env:TMP = $env:TEMP
$env:MAVEN_OPTS = "-Djava.io.tmpdir=$($env:TEMP) -XX:-UsePerfData"
Push-Location $projectRoot
try {
  $mavenArgs = @('-B','-ntp','-gs',"$projectRoot\scripts\settings.xml",'-s',"$projectRoot\scripts\settings.xml","-Dmaven.repo.local=$projectRoot\.cache\m2",'clean','verify')
  if ($SkipTests) { $mavenArgs += '-DskipTests' }
  & mvn @mavenArgs
  if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败。' }
} finally { Pop-Location }
