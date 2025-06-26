<?php
// Define the path to the config file
$config_file = '/data/adb/modules/ZDT-D/php7/files/www/auth/config.json';


// Check if the config file exists
if (!file_exists($config_file)) {
    die('Error: Configuration file not found.');
}

// Load the configuration from the JSON file
$config = json_decode(file_get_contents($config_file), true);

// Define the LOGIN_ENABLED constant based on the JSON file
define('LOGIN_ENABLED', $config['LOGIN_ENABLED']);