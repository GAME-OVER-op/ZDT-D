<?php
// icons.php

// ---------------------------
// 1. Выбор языка (ru/en) через GET-параметр ?lang=ru или ?lang=en
$lang = isset($_GET['lang']) && in_array($_GET['lang'], ['ru','en'])
      ? $_GET['lang']
      : 'ru';

// Строки локализации
$T = [
  'ru' => [
    'page_title'      => 'Управление иконками',
    'replace_heading' => 'Замена иконок',
    'upload_button'   => 'Загрузить',
    'open_in_browser' => 'Пожалуйста, откройте эту страницу в браузере (например, Chrome или Firefox). Web UI не поддерживает загрузку файлов.',
    'save_error'      => 'Ошибка при сохранении',
    'file_updated'    => 'Файл %s обновлён.',
  ],
  'en' => [
    'page_title'      => 'Icon Management',
    'replace_heading' => 'Replace Icons',
    'upload_button'   => 'Upload',
    'open_in_browser' => 'Please open this page in a browser (e.g. Chrome or Firefox). Web UI does not support file uploads.',
    'save_error'      => 'Error saving file',
    'file_updated'    => 'File %s has been updated.',
  ],
];

// ---------------------------
// 2. Серверная проверка User-Agent
$ua = $_SERVER['HTTP_USER_AGENT'] ?? '';
$isEmbedded = stripos($ua, 'webui') !== false; // “WebUI” в UA
$isBrowserUA = preg_match('#(Chrome|Firefox|Edge|Safari)#i', $ua);
$isSupportedUA = !$isEmbedded && $isBrowserUA;

if (!$isSupportedUA) {
    // Если Web UI или неподдерживаемый UA — сразу отвечаем сообщением
    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><html lang="'.$lang.'"><head><meta charset="utf-8">'
       .'<title>'.htmlspecialchars($T[$lang]['page_title']).'</title>'
       .'</head><body>'
       .'<p style="margin:50px;font-size:18px;text-align:center;color:#c00;">'
       .htmlspecialchars($T[$lang]['open_in_browser'])
       .'</p></body></html>';
    exit;
}

// ---------------------------
// 3. Логика обработки POST-загрузки
$iconsDir = '/data/local/tmp/';
$icons    = ['icon.png','icon1.png','icon2.png','icon3.png','icon4.png'];
$message  = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    foreach ($icons as $key => $filename) {
        if (!empty($_FILES["icon{$key}"]['tmp_name'])) {
            $tmp  = $_FILES["icon{$key}"]['tmp_name'];
            $dest = $iconsDir . basename($filename);
            exec("su -c 'cp ".escapeshellarg($tmp)." ".escapeshellarg($dest)."' 2>&1", $out, $code);
            if ($code !== 0) {
                $message .= sprintf(
                    '%s %s: %s<br>',
                    $T[$lang]['save_error'],
                    htmlspecialchars($filename),
                    htmlspecialchars(implode(' ', $out))
                );
            } else {
                $message .= sprintf(
                    htmlspecialchars($T[$lang]['file_updated']).'<br>',
                    htmlspecialchars($filename)
                );
            }
        }
    }
}
?>
<!doctype html>
<html lang="<?= $lang ?>">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title><?= htmlspecialchars($T[$lang]['page_title']) ?></title>
  <link rel="stylesheet" href="/assets/index-DUGZqMTC.css" crossorigin>
  <style>
    body { opacity:0; transition:opacity 1s ease-in-out; }
    body.fade-in { opacity:1; }
    .container {
      max-width:600px; margin:40px auto; padding:20px;
      background:#2a2a2a; border-radius:10px;
    }
    h1 { color:#fff; margin-bottom:20px; }
    .file-row { display:flex; align-items:center; margin-bottom:16px; }
    .file-row label { flex:1; color:#ddd; }
    .file-row input[type="file"] { flex:2; }
    button.upload {
      padding:10px 20px; font-size:16px; color:#fff;
      background:linear-gradient(135deg,#444,#666);
      border:none; border-radius:6px; cursor:pointer;
      animation: shimmer 3s ease-in-out infinite;
    }
    @keyframes shimmer {
      0%,100% { background-position:0% 50%; }
      50% { background-position:100% 50%; }
    }
    .message { margin-top:20px; color:#8f8; }
  </style>
  <script>
    document.addEventListener('DOMContentLoaded', function(){
      // Клиентская проверка на Web UI / iframe / отсутствие Chrome API
      var ua = navigator.userAgent;
      var inIframe   = window.self !== window.top;
      var isWebUI    = /WebUI/i.test(ua);
      var hasChrome  = !!window.chrome && !!navigator.serviceWorker;
      if (inIframe || isWebUI || !hasChrome) {
        var overlay = document.createElement('div');
        overlay.style.cssText = [
          'position:fixed','top:0','left:0','width:100%','height:100%',
          'background:rgba(0,0,0,0.85)','display:flex','align-items:center',
          'justify-content:center','padding:30px','box-sizing:border-box',
          'z-index:9999','color:#fff','font-size:20px','text-align:center'
        ].join(';');
        overlay.textContent = '<?= addslashes($T[$lang]['open_in_browser']) ?>';
        document.body.appendChild(overlay);
      }
    });
    window.addEventListener('load', ()=> document.body.classList.add('fade-in'));
  </script>
</head>
<body class="bg-surface root_layout">
  <div class="container">
    <h1><?= htmlspecialchars($T[$lang]['replace_heading']) ?></h1>
    <?php if ($message): ?>
      <div class="message"><?= $message ?></div>
    <?php endif; ?>
    <form method="post" enctype="multipart/form-data">
      <?php foreach ($icons as $key => $filename): ?>
        <div class="file-row">
          <label for="icon<?= $key ?>"><?= htmlspecialchars($filename) ?>:</label>
          <input
            type="file"
            name="icon<?= $key ?>"
            id="icon<?= $key ?>"
            accept="image/png"
          >
        </div>
      <?php endforeach; ?>
      <button type="submit" class="upload">
        <?= htmlspecialchars($T[$lang]['upload_button']) ?>
      </button>
    </form>
  </div>
</body>
</html>
