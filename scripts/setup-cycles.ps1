[CmdletBinding()]
param(
    [string]$CudaRoot,
    [string]$OptixRoot,
    [ValidateRange(1, 32)]
    [int]$BuildJobs = 6,
    [switch]$ExperimentalDlss,
    [switch]$FetchOnly
)

$ErrorActionPreference = 'Stop'

$cyclesRevision = 'v5.2.0'
$cyclesCommit = '3b97e190c5ff1a2ed2160d879ad5bf95bea7b8ba'
$cyclesLibrariesCommit = '60d6e96b917568278d400a4024c98da0fb777338'
$cyclesRepository = 'https://projects.blender.org/blender/cycles.git'
$dlssSdkCommit = 'a291cc7d2cc642a51566f3dfd5376f635cd1b284'
$dlssSdkRawRoot = "https://raw.githubusercontent.com/NVIDIA/DLSS/$dlssSdkCommit/include"
$dlssRuntimeName = 'nvngx_dlssd.dll'
$dlssRuntimeUrl = "https://raw.githubusercontent.com/NVIDIA/DLSS/$dlssSdkCommit/lib/Windows_x86_64/rel/$dlssRuntimeName"
$dlssRuntimeHash = 'f4e97624f70fbb769acb11ebd751b512ecc9463d4bd6aef04896d3956e6084a0'
$blenderRevision = 'v5.2.0'
$blenderCommit = 'fbe6228777e7d9afefcd61a413844e790ae75db7'
$blenderRepository = 'https://projects.blender.org/blender/blender.git'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dependencyRoot = Join-Path $projectRoot '.deps'
$cyclesVariant = if ($ExperimentalDlss) { 'cycles-dlss' } else { 'cycles' }
$cyclesSource = Join-Path $dependencyRoot $cyclesVariant
$cyclesLibraries = Join-Path $cyclesSource 'lib\windows_x64'
$cyclesBuild = Join-Path $dependencyRoot "${cyclesVariant}-build"
$cyclesInstall = Join-Path $dependencyRoot "${cyclesVariant}-install"
$dlssSdkRoot = Join-Path $dependencyRoot 'dlss-sdk'
$dlssInclude = Join-Path $dlssSdkRoot 'include'
$dlssRuntime = Join-Path $dlssSdkRoot "bin\$dlssRuntimeName"
$blenderSource = Join-Path $dependencyRoot 'blender'
$colorManagementSource = Join-Path $blenderSource 'release\datafiles\colormanagement'
$colorManagementInstall = Join-Path $cyclesInstall 'color\ocio'
$cyclesPatches = @(
    (Join-Path $projectRoot 'patches\cycles-v5.2-cuew-external-semaphore.patch'),
    (Join-Path $projectRoot 'patches\cycles-v5.2-vulkan-interop-sync.patch'),
    (Join-Path $projectRoot 'patches\cycles-v5.2-vulkan-interop-range.patch'),
    (Join-Path $projectRoot 'patches\cycles-v5.2-vulkan-interop-timeline.patch'),
    (Join-Path $projectRoot 'patches\cycles-v5.2-nonemissive-geometry-light-update.patch')
)
if ($ExperimentalDlss) {
    $cyclesPatches += Join-Path $projectRoot 'patches\cycles-v5.2-dlss-experimental.patch'
}
$cyclesPatchStamp = Join-Path $cyclesSource '.cyclesrenderer-patches'
$expectedCyclesPatchState = (@("cycles=$cyclesCommit") + @(
    $cyclesPatches | ForEach-Object {
        "$([System.IO.Path]::GetFileName($_))=$((Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLowerInvariant())"
    }
)) -join "`n"
$dlssHeaders = [ordered]@{
    'nvsdk_ngx.h' = 'f6014a256f9d75ccec1278ac6e23d596b398a76cc3960048ca1a274b378b1989'
    'nvsdk_ngx_defs.h' = 'ea23f33497cd274860d1c25a97644fce807dcb0037c594547203343103fad03e'
    'nvsdk_ngx_params.h' = '943bc8cc5cdae03b6303016fbad3183636f2335ae27a2d18776798c3b4efabbc'
    'nvsdk_ngx_defs_dlssd.h' = 'd2fde340db2189c89bce093bc1edd7b3579df48decac329218a98a9c5fd46018'
}
$requiredLibraryDirectories = @(
    'OpenImageIO',
    'aom',
    'imath',
    'openexr',
    'openjph',
    'opencolorio',
    'openimagedenoise',
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

function Apply-CyclesPatch {
    param(
        [Parameter(Mandatory)]
        [string]$PatchPath
    )

    if (-not (Test-Path -LiteralPath $PatchPath -PathType Leaf)) {
        throw "Cycles patch is missing: $PatchPath"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $git -C $cyclesSource apply --reverse --check -- $PatchPath 2>$null
    $reverseCheckExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($reverseCheckExitCode -eq 0) {
        Write-Host "[cycles] Patch already applied: $([System.IO.Path]::GetFileName($PatchPath))"
        return
    }

    $checkOutput = & $git -C $cyclesSource apply --check -- $PatchPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Cycles patch does not apply cleanly: $PatchPath`n$($checkOutput -join "`n")"
    }
    Invoke-Checked $git -C $cyclesSource apply -- $PatchPath
    Write-Host "[cycles] Applied patch: $([System.IO.Path]::GetFileName($PatchPath))"
}

function Initialize-DlssSdk {
    if (-not (Test-Path -LiteralPath $dlssInclude -PathType Container)) {
        New-Item -ItemType Directory -Path $dlssInclude | Out-Null
    }

    foreach ($header in $dlssHeaders.GetEnumerator()) {
        $destination = Join-Path $dlssInclude $header.Key
        $valid = (Test-Path -LiteralPath $destination -PathType Leaf) -and
            ((Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant() -eq
                $header.Value)
        if (-not $valid) {
            Write-Host "[cycles] Downloading NVIDIA DLSS SDK header: $($header.Key)"
            Invoke-WebRequest -UseBasicParsing -Uri "$dlssSdkRawRoot/$($header.Key)" -OutFile $destination
        }
        $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant()
        if ($actualHash -ne $header.Value) {
            throw "Unexpected SHA-256 for DLSS SDK header $($header.Key): $actualHash"
        }
    }

    $dlssRuntimeDirectory = Split-Path -Parent $dlssRuntime
    if (-not (Test-Path -LiteralPath $dlssRuntimeDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $dlssRuntimeDirectory | Out-Null
    }
    $runtimeValid = (Test-Path -LiteralPath $dlssRuntime -PathType Leaf) -and
        ((Get-FileHash -Algorithm SHA256 -LiteralPath $dlssRuntime).Hash.ToLowerInvariant() -eq
            $dlssRuntimeHash)
    if (-not $runtimeValid) {
        Write-Host "[cycles] Downloading NVIDIA DLSS Ray Reconstruction runtime: $dlssRuntimeName"
        Invoke-WebRequest -UseBasicParsing -Uri $dlssRuntimeUrl -OutFile $dlssRuntime
    }
    $actualRuntimeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $dlssRuntime).Hash.ToLowerInvariant()
    if ($actualRuntimeHash -ne $dlssRuntimeHash) {
        throw "Unexpected SHA-256 for DLSS runtime ${dlssRuntimeName}: $actualRuntimeHash"
    }
    Write-Host "[cycles] DLSS SDK: headers and signed RR runtime from NVIDIA/DLSS@$dlssSdkCommit"
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
if ($ExperimentalDlss) {
    Write-Host '[cycles] DLSS RR : EXPERIMENTAL (explicit opt-in, DLAA 1x only)'
}

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

$patchesAlreadyApplied = $false
$knownAppliedPatchLines = @()
if (Test-Path -LiteralPath $cyclesPatchStamp -PathType Leaf) {
    $actualCyclesPatchState = (Get-Content -LiteralPath $cyclesPatchStamp -Raw).TrimEnd("`r", "`n")
    $actualCyclesPatchLines = @($actualCyclesPatchState -split "`r?`n")
    $knownAppliedPatchLines = $actualCyclesPatchLines
    $expectedCyclesPatchLines = @($expectedCyclesPatchState -split "`r?`n")
    $unrecognizedPatchLines = @(
        $actualCyclesPatchLines | Where-Object { $_ -notin $expectedCyclesPatchLines }
    )
    if ($unrecognizedPatchLines.Count -gt 0) {
        throw "Cycles patch state does not match the requested patch set. Remove only the isolated source directory and rerun: $cyclesSource"
    }
    if ($actualCyclesPatchState -eq $expectedCyclesPatchState) {
        $patchesAlreadyApplied = $true
        Write-Host '[cycles] Patch set already applied and fingerprint verified.'
    } else {
        Write-Host '[cycles] Applying an additive patch-set update.'
    }
}
if (-not $patchesAlreadyApplied) {
    if (-not (Test-Path -LiteralPath $cyclesPatchStamp -PathType Leaf)) {
        $trackedSourceChanges = @(& $git -C $cyclesSource diff --name-only)
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to inspect Cycles source changes at $cyclesSource"
        }
        if ($trackedSourceChanges.Count -gt 0) {
            throw "Cycles source has unrecognized tracked changes and cannot be patched safely: $cyclesSource"
        }
    }
    foreach ($cyclesPatch in $cyclesPatches) {
        $patchStateLine = "$([System.IO.Path]::GetFileName($cyclesPatch))=$((Get-FileHash -Algorithm SHA256 -LiteralPath $cyclesPatch).Hash.ToLowerInvariant())"
        if ($patchStateLine -in $knownAppliedPatchLines) {
            Write-Host "[cycles] Patch already fingerprinted: $([System.IO.Path]::GetFileName($cyclesPatch))"
            continue
        }
        Apply-CyclesPatch -PatchPath $cyclesPatch
    }
    [System.IO.File]::WriteAllText(
        $cyclesPatchStamp,
        $expectedCyclesPatchState + "`n",
        [System.Text.UTF8Encoding]::new($false))
}
if ($ExperimentalDlss) {
    Initialize-DlssSdk
}

if (-not (Test-Path -LiteralPath $blenderSource)) {
    Invoke-Checked $git clone --filter=blob:none --no-checkout --depth 1 --branch $blenderRevision $blenderRepository $blenderSource
    Invoke-Checked $git -C $blenderSource sparse-checkout set release/datafiles/colormanagement
    Invoke-Checked $git -C $blenderSource checkout
} elseif (-not (Test-Path -LiteralPath (Join-Path $blenderSource '.git'))) {
    throw "Existing Blender source is not a Git checkout: $blenderSource"
}

$actualBlenderCommit = & $git -C $blenderSource rev-parse HEAD
if ($LASTEXITCODE -ne 0) {
    throw "Unable to inspect Blender revision at $blenderSource"
}
$actualBlenderCommit = $actualBlenderCommit.Trim()
if ($actualBlenderCommit -ne $blenderCommit) {
    throw "Unexpected Blender revision at ${blenderSource}: expected $blenderCommit, got $actualBlenderCommit"
}
if (-not (Test-Path -LiteralPath (Join-Path $colorManagementSource 'config.ocio') -PathType Leaf)) {
    throw "Blender color-management assets are missing: $colorManagementSource"
}
Write-Host "[cycles] OCIO     : $colorManagementSource ($blenderCommit)"

if ($FetchOnly) {
    Write-Host '[cycles] Source, minimal Windows dependencies, and Blender OCIO assets are ready.'
    exit 0
}

$cmakeCudaRoot = $resolvedCudaRoot.Replace('\', '/')
$cmakeOptixRoot = $resolvedOptixRoot.Replace('\', '/')
$cmakeDlssInclude = $dlssInclude.Replace('\', '/')
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
    '-DWITH_CYCLES_OPENIMAGEDENOISE=ON',
    '-DWITH_CYCLES_OPENSUBDIV=OFF',
    '-DWITH_CYCLES_OPENVDB=OFF',
    '-DWITH_CYCLES_NANOVDB=OFF',
    '-DWITH_CYCLES_OSL=OFF',
    '-DWITH_CYCLES_USD=OFF',
    '-DWITH_LIBS_PRECOMPILED=ON'
)
if ($ExperimentalDlss) {
    $configureArguments += @(
        '-DWITH_CYCLES_DLSS_EXPERIMENTAL=ON',
        "-DDLSS_INCLUDE_DIR=$cmakeDlssInclude"
    )
}
Invoke-Checked $cmake @configureArguments
Invoke-Checked $cmake --build $cyclesBuild --config Release --target install --parallel $BuildJobs

if ($ExperimentalDlss) {
    Copy-Item -LiteralPath $dlssRuntime -Destination (Join-Path $cyclesInstall $dlssRuntimeName) -Force
}

if (-not (Test-Path -LiteralPath $colorManagementInstall -PathType Container)) {
    New-Item -ItemType Directory -Path $colorManagementInstall | Out-Null
}
Copy-Item -LiteralPath (Join-Path $colorManagementSource 'config.ocio') -Destination $colorManagementInstall -Force
foreach ($assetDirectory in @('filmic', 'icc', 'luts')) {
    Copy-Item -LiteralPath (Join-Path $colorManagementSource $assetDirectory) -Destination $colorManagementInstall -Recurse -Force
}

$cyclesExecutable = Join-Path $cyclesInstall 'cycles.exe'
if (-not (Test-Path -LiteralPath $cyclesExecutable -PathType Leaf)) {
    throw "Cycles executable was not produced: $cyclesExecutable"
}
Write-Host "[cycles] Build ready: $cyclesExecutable"
