<#
.SYNOPSIS
  Creates a GitHub repository named "Junie the Genie" with two branches (main and developer) and pushes the project code to the developer branch.

.DESCRIPTION
  This script uses Git and the GitHub CLI (gh) to:
    - Initialize a local Git repo if needed
    - Create a new GitHub repository under your account or org
    - Create and push two branches: developer (with full code) and main (empty initial commit)
    - Set the remote "origin" and push the branches

.PARAMETER Owner
  GitHub username or organization to create the repository under. If omitted, your authenticated GitHub user is used.

.PARAMETER Visibility
  public | private (default: private)

.PARAMETER RepoName
  Name of the repository to create (default: "Junie the Genie").

.EXAMPLE
  ./publish-to-github.ps1 -Owner yourUser -Visibility public

.NOTES
  Requirements:
    - PowerShell
    - Git (https://git-scm.com/)
    - GitHub CLI (https://cli.github.com/) and run: gh auth login
  Run this script from the repository root.
#>

param(
  [string]$Owner,
  [ValidateSet('public','private')]
  [string]$Visibility = 'private',
  [string]$RepoName = 'Junie the Genie'
)

function Fail($msg) { Write-Error $msg; exit 1 }

Write-Host "[Info] Starting GitHub publish flow..." -ForegroundColor Cyan

# Verify required tools
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Fail "Git is not installed or not in PATH." }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "GitHub CLI (gh) is not installed or not in PATH." }

# Verify gh authentication
try {
  $authStatus = gh auth status 2>&1
} catch {
  Fail "GitHub CLI not authenticated. Run: gh auth login"
}
if ($authStatus -match 'You are not logged into any GitHub hosts') { Fail "GitHub CLI not authenticated. Run: gh auth login" }

# Initialize git if needed
if (-not (Test-Path .git)) {
  Write-Host "[Info] Initializing git repository..." -ForegroundColor Yellow
  git init | Out-Null
}

# Ensure we are on developer branch for the code push
$currentBranch = git rev-parse --abbrev-ref HEAD 2>$null
if ($LASTEXITCODE -ne 0 -or $currentBranch -eq 'HEAD') { $currentBranch = '' }
if ($currentBranch -ne 'developer') {
  # If branch doesn't exist, create it; otherwise checkout
  $hasDeveloper = git branch --list developer
  if (-not $hasDeveloper) {
    git checkout -b developer | Out-Null
  } else {
    git checkout developer | Out-Null
  }
}

# Stage and commit if needed
$status = git status --porcelain
if ($status) {
  Write-Host "[Info] Staging and committing working tree changes..." -ForegroundColor Yellow
  git add -A
  git commit -m "chore: initial commit for developer branch" | Out-Null
} else {
  # Ensure at least one commit exists on developer branch
  $hasCommit = git rev-parse --verify HEAD 2>$null
  if ($LASTEXITCODE -ne 0) {
    git commit --allow-empty -m "chore: initial empty commit" | Out-Null
  }
}

# Configure remote origin and create GitHub repo if needed
$remoteUrl = ''
try { $remoteUrl = git remote get-url origin 2>$null } catch { $remoteUrl = '' }

# Determine full repo path "owner/name"
if (-not $Owner) {
  # Get current authenticated user as default owner
  $authUser = gh api user --jq .login 2>$null
  if (-not $authUser) { Fail "Unable to detect authenticated GitHub user. Provide -Owner or run gh auth login." }
  $Owner = $authUser
}

$fullName = "$Owner/$RepoName"

function EnsureGhRepoExists($fullName, $visibility) {
  $exists = gh repo view $fullName 2>$null
  if ($LASTEXITCODE -ne 0) {
    Write-Host "[Info] Creating GitHub repo '$fullName' ($visibility)..." -ForegroundColor Yellow
    gh repo create $fullName --$visibility --disable-wiki --disable-issues --confirm | Out-Null
  } else {
    Write-Host "[Info] GitHub repo '$fullName' already exists." -ForegroundColor Green
  }
}

EnsureGhRepoExists -fullName $fullName -visibility $Visibility

# Set or fix remote origin
if (-not $remoteUrl) {
  Write-Host "[Info] Setting remote origin..." -ForegroundColor Yellow
  gh repo set-default $fullName 2>$null | Out-Null
  git remote add origin "https://github.com/$fullName.git" 2>$null | Out-Null
} else {
  Write-Host "[Info] Remote origin already set to: $remoteUrl" -ForegroundColor Green
}

# Push developer with full code
Write-Host "[Info] Pushing developer branch with code..." -ForegroundColor Cyan
git push -u origin developer
if ($LASTEXITCODE -ne 0) { Fail "Failed to push developer branch." }

# Create an empty main branch (no project code) and push it
Write-Host "[Info] Creating empty main branch (orphan) and pushing..." -ForegroundColor Cyan
git checkout --orphan main | Out-Null
git reset | Out-Null
git commit --allow-empty -m "chore: initialize main branch (empty)" | Out-Null
git push -u origin main
if ($LASTEXITCODE -ne 0) { Fail "Failed to push main branch." }

# Switch back to developer for local convenience
git checkout developer | Out-Null

Write-Host "[Success] Repository published: https://github.com/$fullName" -ForegroundColor Green
Write-Host "[Success] Branches pushed: developer (code), main (empty)" -ForegroundColor Green
