<?php
/*
 * Balrog sync endpoint.
 *
 * Upload this one file to your website (for example into public_html), set
 * TOKEN below to a long random secret, and give Balrog the file's address
 * (https://your-site.example/balrog-sync.php) and the same token.
 *
 * What it does: stores ONE file, your vault, exactly as Balrog sends it.
 * The vault is encrypted on your devices before it gets here; this script
 * never sees your master password or anything readable. It only makes sure
 * that the caller knows the token, and that one device cannot overwrite
 * changes made by another without having seen them.
 *
 * Needs PHP 7.0 or newer and nothing else.
 */

// A long random secret, at least 20 characters. Balrog's generator can make
// one: copy a 40-character "letters and numbers" password in here.
const TOKEN = 'CHANGE-ME';

// Largest vault accepted, in bytes.
const MAX_BYTES = 33554432;

// ---------------------------------------------------------------------------

header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header('Content-Type: text/plain; charset=utf-8');

function fail($code, $message)
{
    http_response_code($code);
    echo $message, "\n";
    exit;
}

if (TOKEN === 'CHANGE-ME' || strlen(TOKEN) < 20) {
    fail(503, 'Set TOKEN in this script to a long random secret first.');
}

$given = isset($_SERVER['HTTP_X_BALROG_TOKEN']) ? $_SERVER['HTTP_X_BALROG_TOKEN'] : '';
if (!hash_equals(TOKEN, $given)) {
    usleep(500000); // slow down guessing
    fail(401, 'Unauthorized.');
}

$dir = __DIR__ . '/balrog-data';
$file = $dir . '/vault.balrog';

if (!is_dir($dir) && !mkdir($dir, 0700)) {
    fail(500, 'Cannot create the data folder.');
}
// The vault is encrypted, but there is still no reason to serve it directly.
if (!is_file($dir . '/.htaccess')) {
    file_put_contents(
        $dir . '/.htaccess',
        "<IfModule mod_authz_core.c>\nRequire all denied\n</IfModule>\n" .
        "<IfModule !mod_authz_core.c>\nDeny from all\n</IfModule>\n"
    );
    file_put_contents($dir . '/index.html', '');
}

// One request at a time, so a read-check-write cannot be interleaved.
$lock = fopen($dir . '/lock', 'c');
if ($lock === false || !flock($lock, LOCK_EX)) {
    fail(500, 'Cannot lock the data folder.');
}

$current = is_file($file) ? file_get_contents($file) : null;
$revision = $current === null ? '' : hash('sha256', $current);

$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    header('X-Balrog-Revision: ' . $revision);
    if ($current === null) {
        http_response_code(204);
        exit;
    }
    header('Content-Type: application/octet-stream');
    header('Content-Length: ' . strlen($current));
    echo $current;
    exit;
}

if ($method === 'POST') {
    $body = file_get_contents('php://input', false, null, 0, MAX_BYTES + 1);
    if ($body === false || $body === '') {
        fail(400, 'Empty upload.');
    }
    if (strlen($body) > MAX_BYTES) {
        fail(413, 'The vault is too large.');
    }
    // The caller must have seen the version it is replacing.
    $base = isset($_SERVER['HTTP_X_BALROG_BASE']) ? $_SERVER['HTTP_X_BALROG_BASE'] : null;
    if ($base !== $revision) {
        header('X-Balrog-Revision: ' . $revision);
        fail(409, 'The vault changed; fetch it, merge, and try again.');
    }
    // Write beside the target and rename, so a crash never leaves half a file;
    // keep the previous version as a safety net.
    $temp = $file . '.tmp';
    if (file_put_contents($temp, $body) !== strlen($body)) {
        @unlink($temp);
        fail(500, 'Cannot write the vault (disk full?).');
    }
    if ($current !== null) {
        @copy($file, $dir . '/vault.previous.balrog');
    }
    if (!rename($temp, $file)) {
        fail(500, 'Cannot replace the vault.');
    }
    header('X-Balrog-Revision: ' . hash('sha256', $body));
    echo "Stored.\n";
    exit;
}

header('Allow: GET, POST');
fail(405, 'Method not allowed.');
