<#
.SYNOPSIS
  Pigeon: runs every scenario against a running Pigeon and prints PASS/FAIL for each.

.DESCRIPTION
  Works in Windows PowerShell 5.1 and PowerShell 7. Run from the project root.

    .\scripts\run-scenarios.ps1                         # app already running on :8080
    .\scripts\run-scenarios.ps1 -CleanStart -StartApp   # wipe everything, start fresh, run, stop the app

  -CleanStart  docker compose down -v, then up -d, and waits for both containers to be healthy.
               This deletes the local database. Stop a running Pigeon first (or use -StartApp).
  -StartApp    starts Pigeon with the local profile in the background (log: target\pigeon-run.log)
               and stops it at the end.

  Writes target\scenario-report.md. Exit code = number of failed scenarios.
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$LocalStackUrl = "http://localhost:4566",
    [switch]$CleanStart,
    [switch]$StartApp,
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(30)
$run = Get-Date -Format "MMddHHmmss"          # makes every address and key in this run unique
$results = New-Object System.Collections.Generic.List[object]
$app = $null

function Write-Step([string]$Text) { Write-Host ""; Write-Host "== $Text" -ForegroundColor Cyan }

function Invoke-Pigeon {
    param([string]$Method, [string]$Path, $Body = $null, [hashtable]$Headers = @{},
          [string]$ContentType = "application/json", [string]$Accept = "application/json", [string]$Url = $null)
    $target = if ($Url) { $Url } else { "$BaseUrl$Path" }
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $target)
    foreach ($k in $Headers.Keys) { [void]$req.Headers.TryAddWithoutValidation($k, [string]$Headers[$k]) }
    [void]$req.Headers.TryAddWithoutValidation("Accept", $Accept)
    if ($null -ne $Body) {
        $text = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 10 -Compress }
        $req.Content = [System.Net.Http.StringContent]::new($text, [System.Text.Encoding]::UTF8, $ContentType)
    }
    $res = $client.SendAsync($req).GetAwaiter().GetResult()
    $raw = $res.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $json = $null
    $trimmed = if ($raw) { $raw.TrimStart() } else { "" }
    if ($trimmed.StartsWith("{") -or $trimmed.StartsWith("[")) {
        try { $json = $raw | ConvertFrom-Json } catch { $json = $null }
    }
    $hdr = @{}
    foreach ($h in $res.Headers) { $hdr[$h.Key] = ($h.Value -join ",") }
    [pscustomobject]@{ Status = [int]$res.StatusCode; Json = $json; Raw = $raw; Headers = $hdr }
}

function Assert($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }

function Check([string]$Name, [scriptblock]$Test) {
    $started = Get-Date
    try {
        $detail = & $Test
        $secs = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
        $results.Add([pscustomobject]@{ Scenario = $Name; Result = "PASS"; Seconds = $secs; Detail = [string]$detail })
        Write-Host ("PASS  {0,-52} {1}" -f $Name, $detail) -ForegroundColor Green
    } catch {
        $secs = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
        $results.Add([pscustomobject]@{ Scenario = $Name; Result = "FAIL"; Seconds = $secs; Detail = $_.Exception.Message })
        Write-Host ("FAIL  {0,-52} {1}" -f $Name, $_.Exception.Message) -ForegroundColor Red
    }
}

function Wait-Status([string]$Id, [string[]]$Wanted, [int]$Seconds = $TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $r = Invoke-Pigeon GET "/v1/communications/$Id"
        if ($Wanted -contains $r.Json.status) { return $r.Json }
        Start-Sleep -Milliseconds 700
    } while ((Get-Date) -lt $deadline)
    throw "message $Id is '$($r.Json.status)' after ${Seconds}s (wanted $($Wanted -join ' or '))"
}

function Wait-Until([scriptblock]$Condition, [string]$What, [int]$Seconds = $TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 700
    } while ((Get-Date) -lt $deadline)
    throw "timed out after ${Seconds}s waiting for: $What"
}

function New-Receipt([string]$Recipient, [string]$Stream = "TRANSACTIONAL") {
    @{ templateId = "payment-receipt"; recipient = $Recipient; stream = $Stream
       variables = @{ firstName = "Dana"; amount = '$412.60'; paidOn = "November 6, 2026"; last4 = "4417" } }
}

function Send-Email($Body, [string]$Key, [string]$CorrelationId = $null) {
    $h = @{ "Idempotency-Key" = $Key }
    if ($CorrelationId) { $h["X-Correlation-Id"] = $CorrelationId }
    Invoke-Pigeon POST "/v1/communications" $Body $h
}

function Wait-Healthy([int]$Seconds) {
    Wait-Until {
        try { (Invoke-Pigeon GET "/actuator/health").Json.status -eq "UP" } catch { $false }
    } "Pigeon /actuator/health = UP" $Seconds
}

try {
    # ------------------------------------------------------------------ setup
    if ($CleanStart) {
        # docker writes progress to stderr; in Windows PowerShell 5.1 that must not stop the script.
        $ErrorActionPreference = "Continue"
        Write-Step "Clean start: removing containers and the database volume"
        docker compose down -v | Out-Host
        docker compose up -d | Out-Host
        Write-Host "Waiting for pigeon-postgres and pigeon-localstack to be healthy..."
        $deadline = (Get-Date).AddSeconds(180)
        do {
            $pg = (docker inspect -f "{{.State.Health.Status}}" pigeon-postgres) 2>$null
            $ls = (docker inspect -f "{{.State.Health.Status}}" pigeon-localstack) 2>$null
            if ($pg -eq "healthy" -and $ls -eq "healthy") { break }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $deadline)
        if ($pg -ne "healthy" -or $ls -ne "healthy") { throw "containers not healthy (postgres=$pg, localstack=$ls)" }
        docker logs pigeon-localstack 2>&1 | ForEach-Object { "$_" } | Select-String "pigeon-init" | ForEach-Object { Write-Host "  $_" }
        $ErrorActionPreference = "Stop"
    }

    if ($StartApp) {
        Write-Step "Starting Pigeon (local profile); log in target\pigeon-run.log"
        New-Item -ItemType Directory -Force -Path target | Out-Null
        $app = Start-Process -FilePath "cmd.exe" -PassThru -WindowStyle Hidden -ArgumentList @(
            "/c", 'mvn -q spring-boot:run "-Dspring-boot.run.profiles=local" > target\pigeon-run.log 2>&1')
        Wait-Healthy 300
        Write-Host "Pigeon is up."
    } elseif ($CleanStart) {
        Write-Host ""
        Write-Host "Now start Pigeon in another window:  mvn spring-boot:run `"-Dspring-boot.run.profiles=local`"" -ForegroundColor Yellow
        Write-Host "Waiting up to 5 minutes for it..."
        Wait-Healthy 300
    }

    # ------------------------------------------------------------------ scenarios
    Write-Step "Scenarios (run id $run)"

    Check "01 Health: API and AWS resources UP" {
        $r = Invoke-Pigeon GET "/actuator/health"
        Assert ($r.Json.status -eq "UP") "health is $($r.Json.status)"
        Assert ($r.Json.components.pigeonAws.status -eq "UP") "pigeonAws is $($r.Json.components.pigeonAws.status)"
        "queues + topic present"
    }

    Check "02 Templates listed with their merge fields" {
        $r = Invoke-Pigeon GET "/v1/templates"
        Assert ($r.Status -eq 200) "status $($r.Status)"
        Assert (@($r.Json).Count -eq 3) "expected 3 templates, got $(@($r.Json).Count)"
        $receipt = @($r.Json) | Where-Object { $_.id -eq "payment-receipt" }
        Assert ((@($receipt.fields) -join ",") -eq "amount,firstName,last4,paidOn") "fields $($receipt.fields -join ',')"
        "3 templates"
    }

    $deliveredTo = "success+r$run@simulator.amazonses.com"
    $script:firstKey = "run-$run-delivered"
    $script:firstId = $null
    Check "03 Send -> QUEUED -> SENT -> DELIVERED" {
        $r = Send-Email (New-Receipt $deliveredTo) $script:firstKey
        Assert ($r.Status -eq 202) "status $($r.Status): $($r.Raw)"
        Assert ($r.Json.status -eq "QUEUED") "status field $($r.Json.status)"
        $script:firstId = $r.Json.requestId
        $m = Wait-Status $script:firstId @("DELIVERED")
        $types = @($m.events | ForEach-Object { $_.type }) -join ","
        Assert ($types -like "ACCEPTED,QUEUED,SENT*DELIVERY*") "history $types"
        "history $types"
    }

    Check "04 Same key + same body = idempotent replay" {
        $r = Send-Email (New-Receipt $deliveredTo) $script:firstKey
        Assert ($r.Status -eq 202) "status $($r.Status)"
        Assert ($r.Json.requestId -eq $script:firstId) "different requestId $($r.Json.requestId)"
        Assert ($r.Headers["Idempotent-Replayed"] -eq "true") "Idempotent-Replayed=$($r.Headers['Idempotent-Replayed'])"
        "same requestId, nothing sent"
    }

    Check "05 Same key + different body = 422" {
        $r = Send-Email (New-Receipt "someone.else+r$run@example.com") $script:firstKey
        Assert ($r.Status -eq 422) "status $($r.Status)"
        "idempotency-key-reused"
    }

    Check "06 Missing merge fields = 400 listing all of them" {
        $body = @{ templateId = "payment-receipt"; recipient = "dana+r$run@example.com"; stream = "TRANSACTIONAL"; variables = @{ firstName = "Dana" } }
        $r = Send-Email $body "run-$run-missing"
        Assert ($r.Status -eq 400) "status $($r.Status)"
        $missing = @($r.Json.missingFields) -join ","
        Assert ($missing -eq "amount,last4,paidOn") "missingFields $missing"
        "missingFields $missing"
    }

    Check "07 Invalid fields = 400 listing every field" {
        $r = Send-Email '{"templateId":"","recipient":"not-an-email","variables":{}}' "run-$run-invalid"
        Assert ($r.Status -eq 400) "status $($r.Status)"
        $fields = (@($r.Json.errors | ForEach-Object { $_.field }) | Sort-Object) -join ","
        Assert ($fields -eq "recipient,stream,templateId") "fields $fields"
        "errors on $fields"
    }

    Check "08 Unknown template = 400" {
        $body = New-Receipt "dana+r$run@example.com"; $body.templateId = "no-such-template"
        $r = Send-Email $body "run-$run-unknown"
        Assert ($r.Status -eq 400) "status $($r.Status)"
        Assert ($r.Json.type -like "*unknown-template") "type $($r.Json.type)"
        "unknown-template"
    }

    Check "09 XML request accepted" {
        $xml = "<communication><templateId>enrollment-confirmation</templateId><recipient>success+xml$run@simulator.amazonses.com</recipient><stream>TRANSACTIONAL</stream><variables><firstName>Dana</firstName><planName>Gold PPO 2027</planName><effectiveDate>January 1, 2027</effectiveDate><memberId>AB-448210</memberId></variables></communication>"
        $r = Invoke-Pigeon POST "/v1/communications" $xml @{ "Idempotency-Key" = "run-$run-xml" } "application/xml"
        Assert ($r.Status -eq 202) "status $($r.Status): $($r.Raw)"
        Assert ($r.Json.templateVersion -eq 2) "templateVersion $($r.Json.templateVersion)"
        "enrollment-confirmation v2"
    }

    Check "10 BULK stream uses pigeon-bulk-prod and is delivered" {
        $r = Send-Email (New-Receipt "success+bulk$run@simulator.amazonses.com" "BULK") "run-$run-bulk"
        Assert ($r.Json.configurationSet -eq "pigeon-bulk-prod") "configurationSet $($r.Json.configurationSet)"
        [void](Wait-Status $r.Json.requestId @("DELIVERED"))
        "pigeon-bulk-prod, DELIVERED"
    }

    $bounceTo = "bounce+r$run@simulator.amazonses.com"
    Check "11 Hard bounce -> BOUNCED, suppressed, next send SUPPRESSED" {
        $r = Send-Email (New-Receipt $bounceTo) "run-$run-bounce-1"
        [void](Wait-Status $r.Json.requestId @("BOUNCED"))
        $s = Invoke-Pigeon GET "/v1/suppressions/$bounceTo"
        Assert ($s.Json.reason -eq "HARD_BOUNCE") "suppression $($s.Status) $($s.Json.reason)"
        $again = Send-Email (New-Receipt $bounceTo) "run-$run-bounce-2"
        Assert ($again.Json.status -eq "SUPPRESSED") "second send $($again.Json.status)"
        "HARD_BOUNCE, second send SUPPRESSED"
    }

    $complaintTo = "complaint+r$run@simulator.amazonses.com"
    Check "12 Complaint -> COMPLAINED; removal needs force" {
        $r = Send-Email (New-Receipt $complaintTo) "run-$run-complaint"
        [void](Wait-Status $r.Json.requestId @("COMPLAINED"))
        $no = Invoke-Pigeon DELETE "/v1/suppressions/$complaintTo"
        Assert ($no.Status -eq 409) "delete without force: $($no.Status)"
        $yes = Invoke-Pigeon DELETE "/v1/suppressions/${complaintTo}?force=true"
        Assert ($yes.Status -eq 204) "delete with force: $($yes.Status)"
        "409 without force, 204 with force"
    }

    Check "13 SES account suppression list -> ACCOUNT_SUPPRESSION_LIST" {
        $to = "suppressionlist+r$run@simulator.amazonses.com"
        $r = Send-Email (New-Receipt $to) "run-$run-acct"
        [void](Wait-Status $r.Json.requestId @("BOUNCED"))
        $s = Invoke-Pigeon GET "/v1/suppressions/$to"
        Assert ($s.Json.reason -eq "ACCOUNT_SUPPRESSION_LIST") "reason $($s.Json.reason)"
        "ACCOUNT_SUPPRESSION_LIST"
    }

    Check "14 Three soft bounces -> REPEATED_SOFT_BOUNCE" {
        $to = "dana.mailboxfull+r$run@example.com"
        foreach ($i in 1..3) {
            $s = Invoke-Pigeon GET "/v1/suppressions/$to"
            if ($i -lt 3) { Assert ($s.Status -eq 404) "suppressed too early (after $($i - 1))" }
            $r = Send-Email (New-Receipt $to) "run-$run-soft-$i"
            [void](Wait-Status $r.Json.requestId @("BOUNCED"))
        }
        Wait-Until { (Invoke-Pigeon GET "/v1/suppressions/$to").Json.reason -eq "REPEATED_SOFT_BOUNCE" } "soft-bounce suppression"
        "suppressed on the 3rd"
    }

    Check "15 Batch: good, bouncing and invalid rows" {
        $recipients = @(
            @{ recipient = "success+b1r$run@simulator.amazonses.com"; variables = @{ firstName = "Ana" } },
            @{ recipient = "success+b2r$run@simulator.amazonses.com"; variables = @{ firstName = "Ben" } },
            @{ recipient = "success+b3r$run@simulator.amazonses.com"; variables = @{ firstName = "Cy" } },
            @{ recipient = "bounce+b4r$run@simulator.amazonses.com";  variables = @{ firstName = "Dee" } },
            @{ recipient = "bounce+b5r$run@simulator.amazonses.com";  variables = @{ firstName = "Eve" } },
            @{ recipient = "not-an-email";                             variables = @{ firstName = "Fay" } })
        $body = @{ templateId = "payment-receipt"; stream = "BULK"; recipients = $recipients
                   sharedVariables = @{ amount = '$412.60'; paidOn = "November 6, 2026"; last4 = "4417" } }
        $r = Invoke-Pigeon POST "/v1/communications/batch" $body @{ "Idempotency-Key" = "run-$run-batch" }
        Assert ($r.Status -eq 202) "status $($r.Status): $($r.Raw)"
        Assert ($r.Json.counts.QUEUED -eq 5 -and $r.Json.counts.REJECTED -eq 1) "counts $($r.Raw)"
        Wait-Until {
            ((Invoke-Pigeon GET "/v1/suppressions/bounce+b4r$run@simulator.amazonses.com").Status -eq 200) -and
            ((Invoke-Pigeon GET "/v1/suppressions/bounce+b5r$run@simulator.amazonses.com").Status -eq 200)
        } "both batch bounces suppressed"
        "5 queued, 1 rejected, 2 bounces suppressed"
    }

    Check "16 Manual suppression add -> get -> remove" {
        $to = "manual+r$run@example.com"
        $add = Invoke-Pigeon POST "/v1/suppressions" @{ address = $to; detail = "run-scenarios" }
        Assert ($add.Status -eq 201) "add $($add.Status)"
        Assert ((Invoke-Pigeon GET "/v1/suppressions/$to").Json.reason -eq "MANUAL") "not MANUAL"
        Assert ((Invoke-Pigeon DELETE "/v1/suppressions/$to").Status -eq 204) "delete failed"
        Assert ((Invoke-Pigeon GET "/v1/suppressions/$to").Status -eq 404) "still present"
        "201, MANUAL, 204, 404"
    }

    Check "17 Reputation metrics" {
        $m = Invoke-Pigeon GET "/v1/metrics?window=1h"
        Assert ($m.Status -eq 200) "status $($m.Status)"
        Assert ($m.Json.totals.sent -ge 10) "sent $($m.Json.totals.sent)"
        Assert ($m.Json.totals.hardBounced -ge 3) "hardBounced $($m.Json.totals.hardBounced)"
        $bad = Invoke-Pigeon GET "/v1/metrics?window=soon"
        Assert ($bad.Status -eq 400) "bad window gave $($bad.Status)"
        "status $($m.Json.status), sent $($m.Json.totals.sent), hard bounce $($m.Json.totals.bounceRatePercent)%"
    }

    Check "18 Correlation id on success and on errors" {
        $corr = "run-$run-trace"
        $r = Send-Email (New-Receipt "success+trace$run@simulator.amazonses.com") "run-$run-trace" $corr
        Assert ($r.Headers["X-Correlation-Id"] -eq $corr) "response header $($r.Headers['X-Correlation-Id'])"
        Assert ($r.Json.correlationId -eq $corr) "body $($r.Json.correlationId)"
        $err = Invoke-Pigeon GET "/v1/communications/00000000-0000-0000-0000-000000000000" -Headers @{ "X-Correlation-Id" = "$corr-404" }
        Assert ($err.Status -eq 404 -and $err.Json.correlationId -eq "$corr-404") "404 body $($err.Raw)"
        "header, body and error all carry it"
    }

    Check "19 Recent messages list" {
        $r = Invoke-Pigeon GET "/v1/communications?limit=5"
        Assert ($r.Status -eq 200 -and @($r.Json).Count -eq 5) "got $(@($r.Json).Count)"
        $b = Invoke-Pigeon GET "/v1/communications?status=BOUNCED&limit=50"
        Assert ((@($b.Json) | Where-Object { $_.status -ne "BOUNCED" }).Count -eq 0) "filter returned other statuses"
        "5 newest, status filter works"
    }

    Check "20 LocalStack SES received the emails" {
        $r = Invoke-Pigeon GET -Url "$LocalStackUrl/_aws/ses"
        $mine = @($r.Json.messages | Where-Object { @($_.Destination.ToAddresses) -contains $deliveredTo })
        Assert ($mine.Count -eq 1) "expected 1 email to $deliveredTo, found $($mine.Count)"
        Assert ($mine[0].Subject -eq 'Payment received: $412.60') "subject '$($mine[0].Subject)'"
        "subject '$($mine[0].Subject)'"
    }

    Check "21 Pigeon Console and Pigeon Lab are served" {
        $console = Invoke-Pigeon GET "/console/index.html" -Accept "text/html"
        Assert ($console.Status -eq 200 -and $console.Raw -like "*Pigeon Console*") "console $($console.Status)"
        $lab = Invoke-Pigeon GET "/" -Accept "text/html"
        Assert ($lab.Status -eq 200 -and $lab.Raw -like "*Pigeon*") "lab $($lab.Status)"
        "/console and / both 200"
    }
}
finally {
    if ($app) {
        Write-Step "Stopping Pigeon"
        $ErrorActionPreference = "Continue"
        & taskkill /PID $app.Id /T /F 2>&1 | Out-Null
    }
}

# ------------------------------------------------------------------ report
$failed = @($results | Where-Object { $_.Result -eq "FAIL" }).Count
$passed = $results.Count - $failed
Write-Step "Result: $passed passed, $failed failed"

New-Item -ItemType Directory -Force -Path target | Out-Null
$lines = @("# Pigeon scenario run $run", "", "Base URL: $BaseUrl  ", "Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  ",
           "Result: **$passed passed, $failed failed**", "", "| # | Scenario | Result | Seconds | Detail |", "|---|---|---|---|---|")
$i = 0
foreach ($r in $results) {
    $i++
    $lines += "| $i | $($r.Scenario) | $($r.Result) | $($r.Seconds) | $(($r.Detail -replace '\|', '/') -replace '\r?\n', ' ') |"
}
$lines | Set-Content -Path target\scenario-report.md -Encoding UTF8
Write-Host "Report: target\scenario-report.md"
exit $failed
