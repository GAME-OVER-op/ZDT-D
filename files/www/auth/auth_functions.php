<?php
session_start();

// Function to check if the user is logged in
function checkUserLogin() {
    if (!isset($_SESSION['user_id'])) {
        header('Location: /auth/login.php'); // Redirect to login page
        exit;
    }
}

// Function to check if login is enabled
function isLoginEnabled() {
    $config_file = '/data/adb/modules/ZDT-D/php7/files/www/auth/config.json';
    if (file_exists($config_file)) {
        $config = json_decode(file_get_contents($config_file), true);
        return isset($config['LOGIN_ENABLED']) && $config['LOGIN_ENABLED'];
    }
    return false;
}

// Set a flag in session or query parameter if login is disabled
if (!isLoginEnabled()) {
    $_SESSION['login_disabled'] = true; // Set a session flag or
    // header("Location: /?login_disabled=1"); // Or use a query parameter
    // exit;
}
?>
