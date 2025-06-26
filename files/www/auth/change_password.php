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


// Redirect to login if not authenticated
//if (!isset($_SESSION['user_id'])) {
//    $_SESSION['redirect_url'] = $_SERVER['REQUEST_URI'];
//    header('Location: login.php');
//    exit;
//}

// Include credentials file
$credentials = include 'credentials.php';
$stored_username = $credentials['username'];
$stored_hashed_password = $credentials['hashed_password'];

$error = '';
$success = '';

if ($_SERVER['REQUEST_METHOD'] == 'POST') {
    $current_password = $_POST['current_password'];
    $new_username = $_POST['new_username'];
    $new_password = $_POST['new_password'];
    $confirm_new_password = $_POST['confirm_new_password'];

    // Validate current password using the same method as login
    if (password_verify($current_password, $stored_hashed_password)) {
        if ($new_password === $confirm_new_password) {
            // Hash new password
            $new_hashed_password = password_hash($new_password, PASSWORD_DEFAULT);


            // Update credentials.php file
            $credentials_content = "<?php\n";
            $credentials_content .= "if (basename(__FILE__) == basename(\$_SERVER['PHP_SELF'])) {\n";
            $credentials_content .= "    header('Location: /');\n";
            $credentials_content .= "    exit;\n";
            $credentials_content .= "}\n";
            $credentials_content .= "return [\n";
            $credentials_content .= "    'username' => '" . addslashes($new_username) . "',\n";
            $credentials_content .= "    'hashed_password' => '" . addslashes($new_hashed_password) . "',\n";
            $credentials_content .= "];\n";

            file_put_contents('credentials.php', $credentials_content);

            $success = 'Username and password have been updated successfully.';
        } else {
            $error = 'New passwords do not match.';
        }
    } else {
        $error = 'Current password is incorrect.';
    }
}
?>

<!DOCTYPE html>
<html>
<head>
    <title>Изменить пароль</title>
    <!-- Import Materialize CSS -->
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
        .change-password-box {
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
        .success {
            color: green;
            margin-bottom: 20px;
        }
    </style>
</head>
<body>
    <div class="change-password-box">
        <h5>Изменить пароль</h5>
        <?php if ($error) echo "<p class='error'>$error</p>"; ?>
        <?php if ($success) echo "<p class='success'>$success</p>"; ?>
        <form method="post" action="change_password.php">
            <div class="input-field">
                <input type="password" name="current_password" id="current_password" required>
                <label for="current_password">Текущий пароль</label>
            </div>
            <div class="input-field">
                <input type="text" name="new_username" id="new_username" required>
                <label for="new_username">Новое имя пользователя</label>
            </div>
            <div class="input-field">
                <input type="password" name="new_password" id="new_password" required>
                <label for="new_password">Новый пароль</label>
            </div>
            <div class="input-field">
                <input type="password" name="confirm_new_password" id="confirm_new_password" required>
                <label for="confirm_new_password">Подтвердите новый пароль</label>
                <p>
                    <label>
                        <input type="checkbox" onclick="togglePassword()">
                        <span>Показать пароль</span>
                    </label>
                </p>
            </div>
            <button type="submit" class="btn waves-effect waves-light teal lighten-2">Изменить пароль</button>
        </form>
    </div>
    <!-- Import Materialize JS -->
    <script src="js/materialize.min.js"></script>
    <!-- Custom JavaScript for Show Password -->
    <script>
        function togglePassword() {
            var passwordFields = document.querySelectorAll("input[type=password]");
            passwordFields.forEach(field => {
                if (field.type === "password") {
                    field.type = "text";
                } else {
                    field.type = "password";
                }
            });
        }
    </script>
</body>
</html>