<?php
header('Content-Type: application/json');

// Здесь вы определяете логику проверки состояния сервиса
// Например, проверяем, запущен ли процесс
$command = 'pgrep zapret';
exec($command, $output, $return_var);

// Если процесс найден, возвращаем true (включено)
$state = ($return_var === 0);

echo json_encode([
    'success' => true,
    'state' => $state
]);
