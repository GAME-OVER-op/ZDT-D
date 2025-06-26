<?php
// nothing_new.php

// Автоопределение языка по первому элементу Accept-Language
$al = $_SERVER['HTTP_ACCEPT_LANGUAGE'] ?? '';
$lang = stripos($al, 'ru') === 0 ? 'ru' : 'en';
$msgs = [
    'ru' => 'Ничего нового',
    'en' => 'Nothing new',
];
$message = $msgs[$lang];
?>
<!doctype html>
<html lang="<?= $lang ?>">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title><?= htmlspecialchars($message) ?></title>
  <style>
    :root {
      --bg-light: #f5f5f5;
      --bg-dark:  #1e1e1e;
      --fg-light: #333;
      --fg-dark:  #ddd;
    }
    @media (prefers-color-scheme: dark) {
      body {
        background: var(--bg-dark);
        color: var(--fg-dark);
      }
    }
    @media (prefers-color-scheme: light) {
      body {
        background: var(--bg-light);
        color: var(--fg-light);
      }
    }
    body {
      margin: 0;
      height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      font-family: sans-serif;
      font-size: 1.5rem;
    }
  </style>
</head>
<body>
  <div><?= htmlspecialchars($message) ?></div>
</body>
</html>
