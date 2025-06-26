<?php
// index.php — ваш файл

// 1. Читаем параметр offonservice из файла params
$params_path = '/data/adb/modules/ZDT-D/working_folder/params';
$offonservice = '0';
if (is_readable($params_path)) {
    $lines = file($params_path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    foreach ($lines as $line) {
        // разделяем по первому =
        if (strpos($line, 'offonservice') === 0) {
            list($key, $value) = explode('=', $line, 2);
            $offonservice = trim($value);
            break;
        }
    }
}
// убеждаемся, что значение только '1' или '0'
$offonservice = ($offonservice === '1') ? '1' : '0';

// 2. Определяем длительность показа анимации (в мс)
$loadingDuration = ($offonservice === '1') ? 4000 : 1000;

// 3. Получаем версию zapret
$zapret_version = trim(shell_exec('nfqws --version'));
?>
<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8">
  <meta http-equiv="X-UA-Compatible" content="IE=edge">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Загрузка</title>
  <style>
    /* === Общие стили страницы === */
    body {
      font-family: Arial, sans-serif;
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100vh;
      margin: 0;
      flex-direction: column;
      opacity: 0;
      transition: opacity 1s ease-in-out;
      background-color: var(--bg, #fff);
      color: var(--fg, #000);
    }
    /* === Текст и анимация точек === */
    .loading-container { text-align: center; }
    .dot {
      display: inline-block;
      width: 12px;
      height: 12px;
      margin: 0 4px;
      border-radius: 50%;
      animation: bounce 1.5s infinite;
      background-color: var(--dot-color, #c00);
    }
    .dot:nth-child(1) { animation-delay: 0s; }
    .dot:nth-child(2) { animation-delay: 0.3s; }
    .dot:nth-child(3) { animation-delay: 0.6s; }
    .dot:nth-child(4) { animation-delay: 0.9s; }
    @keyframes bounce {
      0%, 100% { transform: translateY(0); }
      50% { transform: translateY(-15px); }
    }
    .message { margin-top: 20px; font-size: 18px; }

    /* === Авто-тема по системе === */
    @media (prefers-color-scheme: dark) {
      body { background-color: #121212; color: #fff; }
      .dot { background-color: #f00; }
      .settings-btn { background-color: #1E88E5; color: #fff; }
      .settings-btn:hover { background-color: #1565C0; }
    }
    @media (prefers-color-scheme: light) {
      body { background-color: #fff; color: #000; }
      .dot { background-color: #c00; }
      .settings-btn { background-color: #007BFF; color: #fff; }
      .settings-btn:hover { background-color: #0056b3; }
    }

    /* === Контейнер кнопок === */
    .buttons-container {
      display: none;
      flex-direction: column;
      gap: 16px;
      margin-top: 30px;
      width: 90%;
      max-width: 360px;
    }
    /* === Горизонтальная группа кнопок === */
    .horizontal-group {
      display: flex;
      gap: 16px;
      width: 100%;
    }
    /* === Стили кнопок === */
    .settings-btn {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px 16px;
      font-size: 16px;
      border: none;
      border-radius: 4px;
      cursor: pointer;
      transition: transform 0.1s;
      width: 100%;
      text-align: left;
      box-sizing: border-box;
    }
    .settings-btn:hover { transform: translateY(-2px); }
    .btn-icon {
      width: 40px;
      height: 40px;
      flex-shrink: 0;
    }
    @media (max-width: 320px) {
      .settings-btn { font-size: 14px; padding: 10px; }
      .btn-icon { width: 32px; height: 32px; }
      .horizontal-group { flex-direction: column; }
    }

    /* === Стили для версии === */
    #version-info {
      display: none;       /* скрываем до появления кнопок */
      margin-top: 20px;
      font-size: 14px;
      color: #666;
      text-align: center;
    }
  </style>
</head>
<body>
  <div class="loading-container">
    <div class="dot"></div><div class="dot"></div>
    <div class="dot"></div><div class="dot"></div>
  </div>
  <div class="message">Перед тем как выйти из настроек, запустите сервис.</div>

  <!-- Кнопки -->
  <div class="buttons-container">
    <button id="btn-main" class="settings-btn"></button>
    <button id="btn-extra" class="settings-btn"></button>
    <div class="horizontal-group">
      <button id="btn-replace" class="settings-btn"></button>
      <button id="btn-other" class="settings-btn"></button>
    </div>
    <button id="btn-exp" class="settings-btn"></button>
  </div>

  <!-- Контейнер для версии -->
  <div id="version-info"></div>

  <script>
    // 4. Передаём PHP-переменные в JS
    const loadingDuration = <?= $loadingDuration ?>; // мс: 4000 или 1000
    const zapretVersion   = <?= json_encode($zapret_version, JSON_UNESCAPED_UNICODE) ?>;

    window.onload = function() {
      // локализация
      let lang = (navigator.language || navigator.userLanguage)
                   .toLowerCase().startsWith('ru') ? 'ru' : 'en';
      const t = {
        ru: {
          title:   'Загрузка',
          message: 'Перед тем как выйти из настроек, запустите сервис.',
          main:    'Основные настройки',
          extra:   'Резервирование / восстановление',
          replace: 'Замена картинок',
          other:   'Что-то ещё',
          exp:     'Экспериментальные настройки'
        },
        en: {
          title:   'Loading',
          message: 'Before leaving settings, please start the service.',
          main:    'Main settings',
          extra:   'Reservation / recovery',
          replace: 'Replace images',
          other:   'Something else',
          exp:     'Experimental settings'
        }
      };
      document.title = t[lang].title;
      document.querySelector('.message').textContent = t[lang].message;

      // fade-in
      document.body.style.opacity = 1;

      // существующий AJAX-запрос
      fetch('run_command.php', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ state: false })
      })
      .then(res => res.json())
      .then(data => console.log(data.message))
      .catch(err => console.error('Ошибка:', err));

      // через loadingDuration показываем кнопки и версию
      setTimeout(() => {
        // начинаем скрывать страницу
        document.body.style.opacity = 0;

        // даём 1 секунду на анимацию исчезновения
        setTimeout(() => {
          // удаляем анимацию и сообщение
          document.querySelector('.loading-container').remove();
          document.querySelector('.message').remove();

          // собираем кнопки
          const icons = {
            main:    'icon.png',
            extra:   'icon.png',
            replace: 'icon_replace.png',
            other:   'icon_other.png',
            exp:     'icon1.png'
          };
          const buttons = [
            ['btn-main',    icons.main,    t[lang].main,    'setting.php'],
            ['btn-extra',   icons.extra,   t[lang].extra,   'additional_setting.php'],
            ['btn-replace', icons.replace, t[lang].replace, 'replace_images.php'],
            ['btn-other',   icons.other,   t[lang].other,   'experimental.php'],
            ['btn-exp',     icons.exp,     t[lang].exp,     'experimental.php']
          ];
          buttons.forEach(([id, icon, label, href]) => {
            const btn = document.getElementById(id);
            btn.innerHTML = `<img src="${icon}" class="btn-icon" alt=""> ${label}`;
            btn.addEventListener('click', () => window.location.href = href);
          });

          // показываем кнопки
          document.querySelector('.buttons-container').style.display = 'flex';

          // отображаем версию zapret
          const verDiv = document.getElementById('version-info');
          verDiv.textContent = zapretVersion
            ? 'Версия zapret: ' + zapretVersion
            : 'Версия zapret: недоступна';
          verDiv.style.display = 'block';

          // финальный fade-in
          document.body.style.opacity = 1;
        }, 1000);
      }, loadingDuration);
    };
  </script>
</body>
</html>
