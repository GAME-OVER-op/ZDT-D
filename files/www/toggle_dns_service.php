<?php
header('Content-Type: application/json');

// Проверяем метод запроса
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    echo json_encode(['success' => false, 'message' => 'Неверный метод запроса']);
    exit;
}

// Получаем данные из тела запроса
$data = json_decode(file_get_contents('php://input'), true);
if (!isset($data['state'])) {
    echo json_encode(['success' => false, 'message' => 'Состояние не передано']);
    exit;
}

$state = $data['state']; // true для включения, false для выключения

// Выполняем соответствующую команду
if ($state) {
    $command = 'dns_service_module start';
} else {
    $command = 'dns_service_module stop';
}

exec($command, $output, $return_var);

// Проверяем результат выполнения команды
if ($return_var === 0) {
    echo json_encode(['success' => true, 'message' => "Команда '$command' выполнена"]);
} else {
    echo json_encode(['success' => false, 'message' => "Ошибка при выполнении команды '$command'"]);
}
