<?php
header('Content-Type: application/json');

// Проверяем метод запроса
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    echo json_encode(['success' => false, 'message' => 'Неверный метод запроса']);
    exit;
}

// Выполняем команду zapret stop
$command = 'zapret stop';
exec($command, $output, $return_var);

// Обновляем значение в файле
$filePath = '/data/adb/modules/ZDT-D/working_folder/params';

// Читаем содержимое файла
$fileContent = file_get_contents($filePath);

// Проверяем, что файл был успешно открыт
if ($fileContent === false) {
    echo json_encode(['success' => false, 'message' => 'Не удалось прочитать файл']);
    exit;
}

// Ищем строку с переменной offonservice
$pattern = '/^offonservice=\d+/m';
$replacement = 'offonservice=0';

// Заменяем строку с offonservice
$updatedContent = preg_replace($pattern, $replacement, $fileContent);

// Записываем изменённое содержимое обратно в файл
if (file_put_contents($filePath, $updatedContent) === false) {
    echo json_encode(['success' => false, 'message' => 'Не удалось записать в файл']);
    exit;
}

// Проверяем результат выполнения команды
if ($return_var === 0) {
    echo json_encode(['success' => true, 'message' => "Команда '$command' выполнена и файл обновлён"]);
} else {
    echo json_encode(['success' => false, 'message' => "Ошибка при выполнении команды '$command'"]);
}
?>
