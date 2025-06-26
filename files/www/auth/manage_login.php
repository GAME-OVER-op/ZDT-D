<?php
session_start();

require_once '/data/adb/modules/ZDT-D/php7/files/www/auth/auth_functions.php';

// If login is disabled, set the current page but do not redirect to login
if (isset($_SESSION['login_disabled']) && $_SESSION['login_disabled'] === true) {
    // Login is disabled, handle accordingly
    // You can show a message or just let the user stay on the page
    //echo "<p>Login is currently disabled.</p>";
} else {
    // Proceed to check if the user is logged in
    checkUserLogin();
}

// Load the current configuration
$config = json_decode(file_get_contents('config.json'), true);

// Handle form submission to update the configuration
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $config['LOGIN_ENABLED'] = isset($_POST['login_enabled']);
    
    // Save the updated configuration back to the JSON file
    file_put_contents('config.json', json_encode($config, JSON_PRETTY_PRINT));
}

?>

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Enable/disable login</title>
    <!-- Include Materialize CSS -->
    <link href="css/materialize.min.css" rel="stylesheet">
    <!-- Custom Dark Mode CSS -->
    <style>
        body {
            background-color: #121212;
            color: #ffffff;
        }
        .container {
            margin-top: 50px;
        }
        h3 {
            color: #ffffff;
        }
        .switch label {
            color: #ffffff;
        }
        .btn {
            background-color: #26a69a;
        }
        .btn:hover {
            background-color: #2bbbad;
        }
        .switch label .lever {
            background-color: #26a69a;
        }
        .switch label input[type=checkbox]:checked+.lever {
            background-color: #64ffda;
        }
    </style>
</head>
<body>
    <div class="container">
        <h3>Enable / Disable Login</h3>
        <form method="POST">
            <div class="switch">
                <label>
                    Login Disabled
                    <input type="checkbox" name="login_enabled" <?php echo $config['LOGIN_ENABLED'] ? 'checked' : ''; ?>>
                    <span class="lever"></span>
                    Login Enabled
                </label>
            </div>
            <br>
            <button type="submit" class="btn">Save Changes</button>
        </form>
    </div>

    <!-- Include Materialize JS -->
    <script src="js/materialize.min.js"></script>
</body>
</html>
