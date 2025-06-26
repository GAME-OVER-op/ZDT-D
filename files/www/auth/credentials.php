<?php
if (basename(__FILE__) == basename($_SERVER['PHP_SELF'])) {
    header('Location: /');
    exit;
}

return [
    'username' => 'admin',
    'hashed_password' => '$2y$10$VC7N65gPAESPJWYv9JTMDeSj92pYtIzh5Pb5piGc3HHYotpx41a7K' // 12345
];
?>
