<?php
session_start();

// Load credentials
$credentials = include 'credentials.php';
$stored_username = $credentials['username'];
$stored_hashed_password = $credentials['hashed_password'];

$config_file = '/data/adb/modules/ZDT-D/php7/files/www/auth/config.json';

if (!file_exists($config_file)) {
    die('Error: Configuration file not found.');
}

$config = json_decode(file_get_contents($config_file), true);

// Define the LOGIN_ENABLED constant based on the JSON file
define('LOGIN_ENABLED', $config['LOGIN_ENABLED']);

// Check if login is disabled
$login_disabled = !LOGIN_ENABLED;

// If login is disabled, set a session flag or a message variable
if ($login_disabled) {
    $_SESSION['login_disabled'] = true;
}

// Check if the user is already logged in and redirect accordingly
if (isset($_SESSION['user_id'])) {
    $redirect_to = isset($_SESSION['redirect_to']) ? $_SESSION['redirect_to'] : '/';
    unset($_SESSION['redirect_to']);
    header("Location: $redirect_to");
    exit;
}

if ($_SERVER['REQUEST_METHOD'] == 'POST') {
    $username = $_POST['username'];
    $password = $_POST['password'];

    // Validate credentials
    if ($username === $stored_username && password_verify($password, $stored_hashed_password)) {
        $_SESSION['user_id'] = session_id();
        $_SESSION['username'] = $username;
        $redirect_to = isset($_SESSION['redirect_to']) ? $_SESSION['redirect_to'] : '/';
        unset($_SESSION['redirect_to']);
        header("Location: $redirect_to");
        exit;
    } else {
        $error = 'Неверное имя пользователя или пароль.';
    }
}
?>


<!DOCTYPE html>
<html>
<head>
    <title>Login</title>
    <!-- Import Materialize CSS -->
        <!-- CSS Files -->
    <link rel="stylesheet" href="css/materialize.min.css" />
    <!-- Custom Styles for Dark Mode -->
    <style>
        body {
            background-color: #121212;
            color: #ffffff;
            display: flex;
            justify-content: center;
            align-items: center;
            height: 100vh;
            margin: 0;
        }
        .login-box {
            background-color: #1e1e1e;
            padding: 20px;
            border-radius: 10px;
            box-shadow: 0 0 10px rgba(0, 0, 0, 0.5);
            width: 300px;
            text-align: center;
        }
        .input-field input[type=text], .input-field input[type=password] {
            color: #ffffff;
        }
        .input-field label {
            color: #ffffff;
        }
        .input-field input[type=text]:focus + label,
        .input-field input[type=password]:focus + label {
            color: #26a69a !important;
        }
        .input-field input[type=text]:focus,
        .input-field input[type=password]:focus {
            border-bottom: 1px solid #26a69a !important;
            box-shadow: 0 1px 0 0 #26a69a !important;
        }
        .error {
            color: red;
            margin-bottom: 20px;
        }
        .powered-by {
            color: #9e9e9e;
            margin-top: 20px;
        }
    </style>
</head>

<!doctype html>
<html lang="ru">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <link href="/index.70db41ce.css" rel="stylesheet" />
    <script type="module" src="/index.281e69f2.js"></script>
</head>
    <style>
        /* Стили для видео на заднем фоне */
        body {
            position: relative;
            margin: 0;
            padding: 0;
            overflow-x: hidden; /* Убирает прокрутку по оси X */
        }
        #backgroundVideo {
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            object-fit: cover;
            z-index: -2; /* Размещаем под содержимым сайта */
        }
        #overlay {
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: rgba(0, 0, 0, 0.6); /* Полупрозрачный серый слой */
            z-index: -1; /* Размещаем над видео, но под содержимым */
        }
    </style>
</head>
<body class="bg-gray-900 flex flex-col min-h-screen text-white">
    <!-- Видео на заднем фоне -->
    <video id="backgroundVideo" autoplay muted loop>
        <source src="background.mp4" type="video/mp4">
        Ваш браузер не поддерживает видео.
    </video>
    <div id="overlay"></div>


<body>
    <div class="login-box">
        <h5>Вход для настройки модуля</h5>
        <h5>ZDT&D</h5>
        <?php if (isset($error)) echo "<p class='error'>$error</p>"; ?>
        <form method="post" action="login.php">
            <div class="input-field">
                <input type="text" name="username" id="username" required>
                <label for="username">Имя пользователя</label>
            </div>
            <div class="input-field">
                <input type="password" name="password" id="password" required>
                <label for="password">Пароль</label>
                <p>
                    <label>
                        <input type="checkbox" onclick="togglePassword()">
                        <span>Показать пароль</span>
                    </label>
                </p>
            </div>
            <button type="submit" class="btn waves-effect waves-light teal lighten-2">Войти</button>
        </form>
        <div class="powered-by">powered by Ggover</div>
    </div>
    <!-- Import Materialize JS -->
    <script src="js/materialize.min.js"></script>
    <!-- Custom JavaScript for Show Password -->
    <script>
        function togglePassword() {
            var passwordField = document.getElementById("password");
            if (passwordField.type === "password") {
                passwordField.type = "text";
            } else {
                passwordField.type = "password";
            }
        }
    </script>
</body>
</html>