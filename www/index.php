<?php
// Определяем язык браузера (первые 2 символа)
$lang = substr($_SERVER['HTTP_ACCEPT_LANGUAGE'], 0, 2);

if ($lang === 'en') {
    $title        = "Update";
    $version      = "Version 1.1.4";
    $release      = "Stable Release";
    $finalMessage = "Restart your device to apply updates";
    $buttonText   = "Restart";
    $updates      = [
        "Refactored `mobile_iptables_beta`: moved rule-adding logic into new `add_rule(chain, cmd)` helper.",
        "Restored exactly four ipset calls (v4 PREROUTING/OUTPUT + v6 PREROUTING/OUTPUT).",
        "In IP iteration mode, kept retry logic and notifications; `add_rule` now limits to two rules per IP."
    ];
} else {
    $lang         = 'ru';
    $title        = "Обновление";
    $version      = "Версия 1.1.4";
    $release      = "Стабильная версия";
    $finalMessage = "Перезагрузите устройство для применения обновлений";
    $buttonText   = "Перезагрузить";
    $updates      = [
        "Основное изменение в функции `mobile_iptables_beta`: вынесена логика добавления правила в отдельную функцию `add_rule(chain, cmd)`.",
        "В ipset-блоке восстановлены ровно четыре вызова (v4 PREROUTING/OUTPUT + v6 PREROUTING/OUTPUT).",
        "В режиме перебора IP сохранены retry-логи и уведомления из второго скрипта, при этом `add_rule` теперь гарантирует максимум два правила на IP."
    ];
}
?>
<!DOCTYPE html>
<html lang="<?= htmlspecialchars($lang) ?>">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title><?= htmlspecialchars($title) ?></title>
  <style>
    body {
        margin: 0;
        background-color: #000;
        color: #fff;
        font-family: Arial, sans-serif;
        display: flex;
        justify-content: center;
        align-items: center;
        height: 100vh;
        overflow: hidden;
    }
    #container {
        text-align: center;
    }
    #message {
        font-size: 1.2em;
        opacity: 0;
        transition: opacity 1s, transform 1s;
        transform: translateY(20px);
    }
    #restart-btn {
        margin-top: 20px;
        padding: 15px 25px;
        font-size: 1.2em;
        background-color: #ff0000;
        color: #fff;
        border: none;
        border-radius: 5px;
        cursor: pointer;
        opacity: 0;
        pointer-events: none;
        transition: opacity 1s;
    }
    /* Анимация точек внизу экрана */
    .dots {
        position: fixed;
        bottom: 20px;
        width: 100%;
        text-align: center;
    }
    .dots span {
        display: inline-block;
        margin: 0 4px;
        font-size: 36px;
        color: red;
        animation: blink 1.4s infinite both;
    }
    .dots span:nth-child(1) {
        animation-delay: 0s;
    }
    .dots span:nth-child(2) {
        animation-delay: 0.2s;
    }
    .dots span:nth-child(3) {
        animation-delay: 0.4s;
    }
    @keyframes blink {
        0%, 80%, 100% { opacity: 0; }
        40% { opacity: 1; }
    }
  </style>
</head>
<body>
  <div id="container">
      <div id="message"></div>
      <button id="restart-btn" onclick="reloadDevice()"><?= htmlspecialchars($buttonText) ?></button>
  </div>
  <div class="dots">
    <span>.</span><span>.</span><span>.</span>
  </div>
  <script>
      const fadeInTime = 1100;
      const displayTime = 4000;
      const fadeOutTime = 1500;
      const totalTimePerMessage = fadeInTime + displayTime + fadeOutTime;

      const updates = <?= json_encode($updates) ?>;
      const finalMessage = <?= json_encode($finalMessage) ?>;

      const messageEl = document.getElementById("message");
      const restartBtn = document.getElementById("restart-btn");

      function showMessage(text, callback) {
          messageEl.textContent = text;
          messageEl.style.opacity = 0;
          messageEl.style.transform = "translateY(20px)";
          setTimeout(() => {
              messageEl.style.opacity = 1;
              messageEl.style.transform = "translateY(0)";
          }, 50);
          setTimeout(() => {
              messageEl.style.opacity = 0;
              messageEl.style.transform = "translateY(-20px)";
          }, fadeInTime + displayTime);
          setTimeout(() => {
              if (typeof callback === "function") {
                  callback();
              }
          }, totalTimePerMessage);
      }

      function showSequence(index) {
          if (index < updates.length) {
              showMessage(updates[index], function() {
                  showSequence(index + 1);
              });
          } else {
              showMessage(finalMessage, function() {
                  restartBtn.style.display = "inline-block";
                  setTimeout(() => {
                      restartBtn.style.opacity = 1;
                      restartBtn.style.pointerEvents = "auto";
                  }, 50);
              });
          }
      }

      function reloadDevice() {
          fetch('reboot.php')
              .then(response => response.text())
              .then(data => {
                  alert(data);
              })
              .catch(error => {
                  alert('Ошибка: ' + error);
              });
      }

      document.addEventListener("DOMContentLoaded", function() {
          showSequence(0);
      });
  </script>
</body>
</html>
