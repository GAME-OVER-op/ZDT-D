<?php
// additional_setting.php

// Определяем язык по заголовку Accept-Language
$lang = 'en';
if (!empty($_SERVER['HTTP_ACCEPT_LANGUAGE']) && stripos($_SERVER['HTTP_ACCEPT_LANGUAGE'], 'ru') === 0) {
    $lang = 'ru';
}

// Весь массив локализаций
$L_all = array(
    'ru' => array(
        'title'           => 'Резервное копирование / Восстановление',
        'btn_backup'      => 'Сделать бэкап',
        'btn_restore'     => 'Восстановить из бэкапа',
        'msg_success_bk'  => 'Бэкап успешно сохранён в «%s».',
        'msg_no_read'     => 'Не удалось прочитать «%s».',
        'msg_json_err'    => 'Ошибка кодирования JSON.',
        'msg_save_err'    => 'Не удалось сохранить бэкап в «%s».',
        'msg_no_backup'   => 'Файлы бэкапа не найдены в «%s».',
        'msg_json_parse'  => 'Не удалось разобрать JSON из «%s».',
        'msg_success_rs'  => 'Восстановление из «%s» прошло успешно.',
        'msg_fail_file'   => 'Не удалось восстановить «%s».',
    ),
    'en' => array(
        'title'           => 'Backup / Restore',
        'btn_backup'      => 'Make Backup',
        'btn_restore'     => 'Restore from Backup',
        'msg_success_bk'  => 'Backup successfully saved to "%s".',
        'msg_no_read'     => 'Failed to read "%s".',
        'msg_json_err'    => 'JSON encoding error.',
        'msg_save_err'    => 'Failed to save backup to "%s".',
        'msg_no_backup'   => 'No backup files found in "%s".',
        'msg_json_parse'  => 'Failed to parse JSON from "%s".',
        'msg_success_rs'  => 'Restore from "%s" completed successfully.',
        'msg_fail_file'   => 'Failed to restore "%s".',
    )
);
// Берём нужные строки
$L = $L_all[$lang];

// Список файлов в working_folder
$files = array(
    'bye_dpi',
    'ciadpi.conf',
    'config0',
    'config1',
    'ip_ranges3.txt',
    'ip_ranges4.txt',
    'uid_program0',
    'uid_program1',
    'zapret_config0',
    'zapret_config1',
    'zapret_config2',
    'zapret_config3',
    'zapret_config4',
    'zapret_uid0',
    'zapret_uid1',
    'zapret_uid2',
    'zapret_uid3',
    'zapret_uid4',
    'zapret_uid5',
);

// Пути
$baseDir   = '/data/adb/modules/ZDT-D/working_folder';
$backupDir = '/storage/emulated/0';
$prefix    = 'ZDT_backup_';
$pattern   = $backupDir . '/' . $prefix . '*.json';

// Переменные для сообщений
$message = '';
$error   = '';

// Функция поиска последнего файла бэкапа
function getLatestBackup($pattern) {
    $list = glob($pattern);
    if (!$list) {
        return null;
    }
    // сортируем по времени модификации (последний — первый)
    usort($list, function($a, $b) {
        return filemtime($b) <=> filemtime($a);
    });
    return $list[0];
}

// Обработка POST-запроса
if ($_SERVER['REQUEST_METHOD'] === 'POST' && !empty($_POST['action'])) {
    if ($_POST['action'] === 'backup') {
        $data = array();
        foreach ($files as $fname) {
            $path = $baseDir . '/' . $fname;
            if (is_readable($path)) {
                $data[$fname] = file_get_contents($path);
            } else {
                $data[$fname] = null;
                $error .= sprintf($L['msg_no_read'], $fname) . "<br>";
            }
        }
        $json = json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE);
        if ($json === false) {
            $error .= $L['msg_json_err'] . "<br>";
        } else {
            $ts   = date('Y-m-d_H-i-s');
            $name = "{$prefix}{$ts}.json";
            $full = "{$backupDir}/{$name}";
            if (file_put_contents($full, $json) === false) {
                $error .= sprintf($L['msg_save_err'], $name) . "<br>";
            } else {
                $message = sprintf($L['msg_success_bk'], $name);
            }
        }

    } elseif ($_POST['action'] === 'restore') {
        $latest = getLatestBackup($pattern);
        if (!$latest) {
            $error = sprintf($L['msg_no_backup'], $backupDir);
        } else {
            $contents = file_get_contents($latest);
            $data = json_decode($contents, true);
            if (!is_array($data)) {
                $error = sprintf($L['msg_json_parse'], basename($latest));
            } else {
                foreach ($data as $fname => $content) {
                    if ($content === null) continue;
                    $path = $baseDir . '/' . $fname;
                    if (file_put_contents($path, $content) === false) {
                        $error .= sprintf($L['msg_fail_file'], $fname) . "<br>";
                    }
                }
                if ($error === '') {
                    $message = sprintf($L['msg_success_rs'], basename($latest));
                }
            }
        }
    }
}
?><!DOCTYPE html>
<html lang="<?= $lang ?>">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title><?= htmlspecialchars($L['title']) ?></title>
  <style>
    :root {
      --bg: #fff; --fg: #000;
      --btn-bg: #007BFF; --btn-hover: #0056b3;
      --modal-bg: #fff; --modal-fg: #000;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #121212; --fg: #eee;
        --btn-bg: #1E88E5; --btn-hover: #1565C0;
        --modal-bg: #222; --modal-fg: #eee;
      }
    }
    body {
      margin: 0; padding: 40px 20px;
      font-family: Arial, sans-serif;
      background: var(--bg);
      color: var(--fg);
      display: flex; flex-direction: column;
      align-items: center;
      opacity: 0;
      transition: opacity 1s ease-in-out;
    }
    h1 { margin-bottom: 20px; }
    form.buttons {
      display: flex; flex-direction: column;
      gap: 16px; width: 100%; max-width: 320px;
    }
    .btn {
      padding: 12px; font-size: 16px;
      border: none; border-radius: 4px;
      background: var(--btn-bg); color: #fff;
      cursor: pointer; transition: background 0.2s;
    }
    .btn:hover { background: var(--btn-hover); }
    .modal {
      position: fixed; inset: 0;
      background: rgba(0,0,0,0.5);
      display: flex; align-items: center; justify-content: center;
      visibility: hidden; opacity: 0;
      transition: opacity 0.3s;
    }
    .modal.show {
      visibility: visible; opacity: 1;
    }
    .modal-content {
      background: var(--modal-bg);
      color: var(--modal-fg);
      padding: 20px; border-radius: 6px;
      max-width: 90%; text-align: center;
      position: relative;
    }
    .modal-close {
      position: absolute; top: 8px; right: 12px;
      font-size: 18px; cursor: pointer;
    }
    .message { margin: 0; }
  </style>
</head>
<body>
  <h1><?= htmlspecialchars($L['title']) ?></h1>

  <form method="post" class="buttons">
    <button type="submit" name="action" value="backup"  class="btn">
      <?= htmlspecialchars($L['btn_backup']) ?>
    </button>
    <button type="submit" name="action" value="restore" class="btn">
      <?= htmlspecialchars($L['btn_restore']) ?>
    </button>
  </form>

  <div id="modal" class="modal<?= ($message || $error) ? ' show' : '' ?>">
    <div class="modal-content">
      <span id="close" class="modal-close">&times;</span>
      <?php if ($message): ?>
        <p class="message"><?= htmlspecialchars($message) ?></p>
      <?php endif ?>
      <?php if ($error): ?>
        <p class="message" style="color: #d00;"><?= $error ?></p>
      <?php endif ?>
    </div>
  </div>

  <script>
    window.addEventListener(
'load', function(){
      document.body.style.opacity = '1';
    });
    document.getElementById('close').onclick = function(){
      document.getElementById('modal').classList.remove('show');
    };
  </script>
</body>
</html>