<?php
/*
 * Balrog sync endpoint.
 *
 * Upload this file to your website (for example into public_html/balrog/),
 * then create a second file next to it called balrog-config.php holding your
 * token (copy balrog-config.sample.php, or see the README):
 *
 *     <?php return array('token' => 'a-long-random-secret-of-at-least-20-characters');
 *
 * Give Balrog this script's address (https://your-site.example/balrog/balrog-sync.php)
 * and the same token.
 *
 * The token lives in balrog-config.php, not here, so you can replace this
 * script with a newer version at any time without touching your token. Never
 * upload a file with that name from anywhere else: it would overwrite yours.
 *
 * What it does: stores ONE file, your vault, exactly as Balrog sends it.
 * The vault is encrypted on your devices before it gets here; this script
 * never sees your master password or anything readable. It only makes sure
 * that the caller knows the token, and that one device cannot overwrite
 * changes made by another without having seen them.
 *
 * Needs PHP 7.0 or newer and nothing else.
 */

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

// The token comes from balrog-config.php. That file is PHP that only returns
// a value, so opening it in a browser shows nothing, and the token cannot be
// read out of it over the web.
$token = '';
$configFile = __DIR__ . '/balrog-config.php';
if (is_file($configFile)) {
    $config = include $configFile;
    if (is_array($config) && isset($config['token']) && is_string($config['token'])) {
        $token = $config['token'];
    }
}
if (strlen($token) < 20) {
    fail(503, 'No token yet: create balrog-config.php next to this script (at least 20 characters). See the README.');
}

$given = isset($_SERVER['HTTP_X_BALROG_TOKEN']) ? $_SERVER['HTTP_X_BALROG_TOKEN'] : '';
if (!hash_equals($token, $given)) {
    usleep(500000); // slow down guessing
    fail(401, 'Unauthorized.');
}

$dir = __DIR__ . '/balrog-data';
$file = $dir . '/vault.php';
$previous = $dir . '/vault.previous.php';

// The vault is stored inside a PHP file that refuses to run, so that even
// on a server that ignores .htaccess it cannot be downloaded directly.
const GUARD = "<?php http_response_code(404); exit; ?>\n";

if (!is_dir($dir) && !mkdir($dir, 0700)) {
    fail(500, 'Cannot create the data folder.');
}
// Belt and braces: also tell the web server not to serve this folder.
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

$current = null;
if (is_file($file)) {
    $stored = file_get_contents($file);
    if ($stored !== false && strncmp($stored, GUARD, strlen(GUARD)) === 0) {
        $current = substr($stored, strlen(GUARD));
    }
}
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
    if (file_put_contents($temp, GUARD . $body) !== strlen(GUARD) + strlen($body)) {
        @unlink($temp);
        fail(500, 'Cannot write the vault (disk full?).');
    }
    if ($current !== null) {
        @copy($file, $previous);
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
