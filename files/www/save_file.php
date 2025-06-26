<?php
// Получаем данные из POST-запроса
$filePath = $_POST['filepath'] ?? '';
$content = $_POST['content'] ?? '';

// Проверяем, что путь и содержимое не пустые
if ($filePath && $content) {
    // Проверяем, существует ли файл
    if (file_exists($filePath)) {
        // Записываем новые данные в файл
        $result = file_put_contents($filePath, $content);
        
        if ($result === false) {
            echo 'Не удалось сохранить файл.';
        } else {
            echo 'Файл успешно сохранен.';
        }
    } else {
        echo 'Файл не найден.';
    }
} else {
    echo 'Не указаны необходимые данные.';
}
?>
