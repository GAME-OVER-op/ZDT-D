<?php
// Получаем путь к файлу из запроса
$filePath = $_POST['filepath'] ?? '';

// Проверяем, существует ли файл
if (file_exists($filePath)) {
    // Чтение содержимого файла
    $fileContents = file_get_contents($filePath);
    
    // Проверка на ошибки при чтении файла
    if ($fileContents === false) {
        echo 'Не удалось прочитать файл.';
    } else {
        // Отправляем содержимое файла обратно
        echo htmlspecialchars($fileContents);
    }
} else {
    echo 'Файл не найден.';
}
?>
