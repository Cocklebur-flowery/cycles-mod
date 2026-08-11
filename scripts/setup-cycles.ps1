[CmdletBinding()]
param(
    [string]$CudaRoot,
    [string]$OptixRoot,
    [ValidateRange(1, 32)]
    [int]$BuildJobs = 6,
    [switch]$FetchOnly
)

$ErrorActionPreference = 'Stop'

$cyclesRevision = 'v5.2.0'
$cyclesCommit = '3b97e190c5ff1a2ed2160d879ad5bf95bea7b8ba'
$cyclesLibrariesCommit = '60d6e96b917568278d400a4024c98da0fb777338'
$cyclesRepository = 'https://projects.blender.org/blender/cycles.git'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dependencyRoot = Join-Path $projectRoot '.deps'
$cyclesSource = Join-Path $dependencyRoot 'cycles'
$cyclesLibraries = Join-Path $cyclesSource 'lib\windows_x64'
$cyclesBuild = Join-Path $dependencyRoot 'cycles-build'
$cyclesInstall = Join-Path $dependencyRoot 'cycles-install'
$requiredLibraryDirectories = @(
    'OpenImageIO',
    'aom',
    'imath',
    'openexr',
    'openjph',
    'opencolorio',
    'pthreads',
    'pugixml',
    'tbb',
    'zlib',
    'zstd'
)

function Invoke-Checked {
    param(
        [Parameter(Mandatory)]
        [string]$Executable,
        [Parameter(ValueFromRemainingArguments)]
        [string[]]$Arguments
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $Executable $($Arguments -join ' ')"
    }
}

function Get-MissingLibraryObjects {
    $lfsFiles = & $git -C $cyclesLibraries lfs ls-files -s
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect Git LFS objects in $cyclesLibraries"
    }

    $missingObjects = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $lfsFiles) {
        if ($line -notmatch '^[0-9a-f]+ - (.+) \([^)]+\)$') {
            continue
        }

        $path = $Matches[1]
        foreach ($directory in $requiredLibraryDirectories) {
            if ($path -like "$directory/*") {
                $missingObjects.Add($path)
                break
            }
        }
    }
    return $missingObjects
}

function Find-CudaRoot {
    if ($CudaRoot) {
        return [System.IO.Path]::GetFullPath($CudaRoot)
    }

    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:CUDA_PATH) {
        $candidates.Add($env:CUDA_PATH)
    }

    $uninstallRoots = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
    )
    foreach ($uninstallRoot in $uninstallRoots) {
        Get-ChildItem -LiteralPath $uninstallRoot -ErrorAction SilentlyContinue | ForEach-Object {
            $product = Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue
            if ($product.DisplayName -like 'NVIDIA CUDA Toolkit *' -and $product.InstallLocation) {
                $candidates.Add($product.InstallLocation)
            }
        }
    }

    $standardRoot = 'C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA'
    Get-ChildItem -LiteralPath $standardRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { $candidates.Add($_.FullName) }

    foreach ($candidate in $candidates) {
        $resolved = [System.IO.Path]::GetFullPath($candidate)
        if ((Test-Path -LiteralPath (Join-Path $resolved 'bin\nvcc.exe') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $resolved 'include\cuda.h') -PathType Leaf)) {
            return $resolved
        }
    }
    throw 'A complete CUDA Toolkit installation was not found. Pass -CudaRoot explicitly.'
}

function Find-OptixRoot {
    if ($OptixRoot) {
        return [System.IO.Path]::GetFullPath($OptixRoot)
    }

    $searchRoots = @(
        'C:\ProgramData\NVIDIA Corporation',
        'C:\Program Files\NVIDIA Corporation'
    )
    foreach ($searchRoot in $searchRoots) {
        $candidate = Get-ChildItem -LiteralPath $searchRoot -Directory -Filter 'OptiX SDK *' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'include\optix.h') -PathType Leaf } |
            Select-Object -First 1
        if ($candidate) {
            return $candidate.FullName
        }
    }
    throw 'An OptiX SDK installation was not found. Pass -OptixRoot explicitly.'
}

$git = (Get-Command git -ErrorAction Stop).Source
$cmake = (Get-Command cmake -ErrorAction Stop).Source
$resolvedCudaRoot = Find-CudaRoot
$resolvedOptixRoot = Find-OptixRoot

Write-Host "[cycles] Project : $projectRoot"
Write-Host "[cycles] CUDA    : $resolvedCudaRoot"
Write-Host "[cycles] OptiX   : $resolvedOptixRoot"
Write-Host "[cycles] Source  : $cyclesSource ($cyclesRevision)"

if (-not (Test-Path -LiteralPath $dependencyRoot -PathType Container)) {
    New-Item -ItemType Directory -Path $dependencyRoot | Out-Null
}

$env:GIT_CONFIG_COUNT = '3'
$env:GIT_CONFIG_KEY_0 = 'http.sslBackend'
$env:GIT_CONFIG_VALUE_0 = 'openssl'
$env:GIT_CONFIG_KEY_1 = 'safe.directory'
$env:GIT_CONFIG_VALUE_1 = $cyclesSource
$env:GIT_CONFIG_KEY_2 = 'safe.directory'
$env:GIT_CONFIG_VALUE_2 = $cyclesLibraries

if (-not (Test-Path -LiteralPath $cyclesSource)) {
    Invoke-Checked $git clone --depth 1 --branch $cyclesRevision $cyclesRepository $cyclesSource
} elseif (-not (Test-Path -LiteralPath (Join-Path $cyclesSource '.git'))) {
    throw "Existing Cycles source is not a Git checkout: $cyclesSource"
}

$actualCommit = & $git -C $cyclesSource rev-parse HEAD
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect Cycles revision at $cyclesSource"
}
$actualCommit = $actualCommit.Trim()
if ($actualCommit -ne $cyclesCommit) {
    throw "Unexpected Cycles revision at ${cyclesSource}: expected $cyclesCommit, got $actualCommit"
}

if (-not (Test-Path -LiteralPath (Join-Path $cyclesLibraries '.git'))) {
    $previousSkipSmudge = $env:GIT_LFS_SKIP_SMUDGE
    $env:GIT_LFS_SKIP_SMUDGE = '1'
    try {
        Invoke-Checked $git -C $cyclesSource submodule update --init --depth 1 lib/windows_x64
    } finally {
        if ($null -eq $previousSkipSmudge) {
            Remove-Item Env:\GIT_LFS_SKIP_SMUDGE -ErrorAction SilentlyContinue
        } else {
            $env:GIT_LFS_SKIP_SMUDGE = $previousSkipSmudge
        }
    }
}

$actualLibrariesCommit = & $git -C $cyclesLibraries rev-parse HEAD
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect Windows libraries revision at $cyclesLibraries"
}
$actualLibrariesCommit = $actualLibrariesCommit.Trim()
if ($actualLibrariesCommit -ne $cyclesLibrariesCommit) {
    throw "Unexpected Windows libraries revision: expected $cyclesLibrariesCommit, got $actualLibrariesCommit"
}

Invoke-Checked $git -C $cyclesLibraries lfs install --local
$missingObjects = @(Get-MissingLibraryObjects)
if ($missingObjects.Count -gt 0) {
    $requiredLfsInclude = ($requiredLibraryDirectories | ForEach-Object { "$_/**" }) -join ','
    Write-Host "[cycles] Downloading $($missingObjects.Count) required library objects only."
    Invoke-Checked $git -C $cyclesLibraries lfs pull "--include=$requiredLfsInclude"
    $missingObjects = @(Get-MissingLibraryObjects)
}
if ($missingObjects.Count -gt 0) {
    throw "Required Git LFS objects are still missing:`n$($missingObjects -join "`n")"
}
Write-Host "[cycles] Libraries: $cyclesLibraries ($cyclesLibrariesCommit)"

if ($FetchOnly) {
    Write-Host '[cycles] Source and minimal Windows dependencies are ready.'
    exit 0
}

$cmakeCudaRoot = $resolvedCudaRoot.Replace('\', '/')
$cmakeOptixRoot = $resolvedOptixRoot.Replace('\', '/')
$env:CUDA_PATH = $cmakeCudaRoot
$env:Path = (Join-Path $resolvedCudaRoot 'bin') + [System.IO.Path]::PathSeparator + $env:Path

$configureArguments = @(
    '-S', $cyclesSource,
    '-B', $cyclesBuild,
    '-G', 'Visual Studio 17 2022',
    '-A', 'x64',
    "-DCMAKE_INSTALL_PREFIX=$cyclesInstall",
    "-DCUDAToolkit_ROOT=$cmakeCudaRoot",
    "-DCUDA_TOOLKIT_ROOT_DIR=$cmakeCudaRoot",
    "-DOPTIX_ROOT_DIR=$cmakeOptixRoot",
    "-DCYCLES_RUNTIME_OPTIX_ROOT_DIR=$cmakeOptixRoot",
    '-DWITH_CYCLES_DEVICE_CUDA=ON',
    '-DWITH_CYCLES_DEVICE_OPTIX=ON',
    '-DWITH_CYCLES_CUDA_BINARIES=ON',
    '-DCYCLES_CUDA_BINARIES_ARCH=sm_120',
    '-DWITH_CYCLES_DEVICE_HIP=OFF',
    '-DWITH_CYCLES_DEVICE_HIPRT=OFF',
    '-DWITH_CYCLES_HIP_BINARIES=OFF',
    '-DWITH_CYCLES_DEVICE_ONEAPI=OFF',
    '-DWITH_CYCLES_ONEAPI_BINARIES=OFF',
    '-DWITH_CYCLES_HYDRA_RENDER_DELEGATE=OFF',
    '-DWITH_CYCLES_PATH_GUIDING=OFF',
    '-DWITH_CYCLES_STANDALONE_GUI=OFF',
    '-DWITH_CYCLES_ALEMBIC=OFF',
    '-DWITH_CYCLES_EMBREE=OFF',
    '-DWITH_CYCLES_LOGGING=OFF',
    '-DWITH_CYCLES_OPENIMAGEDENOISE=OFF',
    '-DWITH_CYCLES_OPENSUBDIV=OFF',
    '-DWITH_CYCLES_OPENVDB=OFF',
    '-DWITH_CYCLES_NANOVDB=OFF',
    '-DWITH_CYCLES_OSL=OFF',
    '-DWITH_CYCLES_USD=OFF',
    '-DWITH_LIBS_PRECOMPILED=ON'
)
Invoke-Checked $cmake @configureArguments
Invoke-Checked $cmake --build $cyclesBuild --config Release --target install --parallel $BuildJobs

$cyclesExecutable = Join-Path $cyclesInstall 'cycles.exe'
if (-not (Test-Path -LiteralPath $cyclesExecutable -PathType Leaf)) {
    throw "Cycles executable was not produced: $cyclesExecutable"
}
Write-Host "[cycles] Build ready: $cyclesExecutable"
