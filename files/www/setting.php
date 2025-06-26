<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title data-i18n="title">ZDT-D</title>
  <link rel="stylesheet" crossorigin href="/assets/index-DUGZqMTC.css">
  <style>
    /* Стили модального окна */
    #fileModal {
      display: none;
      position: fixed;
      top: 0;
      left: 0;
      width: 100%;
      height: 100%;
      background: rgba(0,0,0,0.75);
      align-items: center;
      justify-content: center;
      z-index: 50;
    }
    #fileModal .modal-content {
      background: #333;
      padding: 20px;
      border-radius: 10px;
      width: 90%;
      max-width: 500px;
    }
    #fileModal textarea {
      width: 100%;
      height: 200px;
      background: #555;
      border: none;
      color: white;
      padding: 10px;
      resize: none;
    }
    
    /* Плавное появление страницы */
    body {
      opacity: 0;
      transition: opacity 1s ease-in-out;
    }
    body.fade-in {
      opacity: 1;
    }
    
    /* Стили для кнопки "Поддержать автора" */
    .support-author {
      position: fixed;
      bottom: 20px;
      right: 20px;
      padding: 12px 20px;
      font-size: 16px;
      color: #fff;
      background: linear-gradient(135deg, #222, #444, #333, #555);
      background-size: 200% 200%;
      border: none;
      border-radius: 8px;
      cursor: pointer;
      z-index: 100;
      /* Объединяем две анимации: переливание и редкое подпрыгивание */
      animation: shimmer 3s ease-in-out infinite, rareBounce 60s infinite;
    }
    
    @keyframes shimmer {
      0%   { background-position: 0% 50%; }
      50%  { background-position: 100% 50%; }
      100% { background-position: 0% 50%; }
    }
    
    @keyframes rareBounce {
      0%, 90%, 100% { transform: translateY(0); }
      92% { transform: translateY(-8px); }
      94% { transform: translateY(0); }
    }
  </style>
</head>
<body class="bg-surface flex justify-center min-h-screen root_layout">
  <!-- Селектор языка -->
  <div style="position: absolute; top: 10px; right: 10px;">
    <select id="languageSelector">
      <option value="en">English</option>
      <option value="ru">Русский</option>
    </select>
  </div>
  
  <main class="p-3 text-sm w-full max-w-2xl">
    <!-- Заголовок страницы -->
    <h1 data-i18n="title" class="text-left text-lg font-semibold mt-2 ml-1 mb-6"></h1>
    
    <!-- Блок информации -->
    <div class="bg-surface-container mb-4 p-4 rounded-lg">
      <div class="flex items-center justify-between">
        <h2 class="text-on-surface font-semibold text-lg" data-i18n="infoTitle"></h2>
        <button class="btn btn-primary" onclick="window.location.href='instr/index.html'" data-i18n="readButton"></button>
      </div>
    </div>
    
    <!-- Блок настроек -->
    <div class="bg-surface-container mb-4 p-4 rounded-lg">
      <h2 class="font-semibold mb-4 text-lg" data-i18n="settingsTitle"></h2>
    
    <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="byedpiconfig"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/ciadpi.conf')"
                data-i18n="edit"></button>
      </div>
      
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="byedpilist"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/bye_dpi')"
                data-i18n="edit"></button>
      </div>
      
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="configDpiTunnel0"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/config0')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="configDpiTunnel1"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/config1')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="listAppsDpiTunnel0"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/uid_program0')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="listAppsDpiTunnel1"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/uid_program1')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="configZapret0"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_config0')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="configZapret1"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_config1')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="configZapret2"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_config2')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="configZapret3"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_config3')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="configZapret4"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_config4')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="listAppsZapret0"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_uid0')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="listAppsZapret1"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_uid1')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="listAppsZapret2"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_uid2')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="listAppsZapret3"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_uid3')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="listAppsZapret4"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_uid4')"
                data-i18n="edit"></button>
      </div>
      
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="listAppsZapret5"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/zapret_uid5')"
                data-i18n="edit"></button>
      </div>
    
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="listIp3"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/ip_ranges3.txt')"
                data-i18n="edit"></button>
      </div>
      <div class="flex items-center justify-between mb-4">
        <p class="text-on-surface" data-i18n="listIp4"></p>
        <button class="btn btn-outline"
                onclick="loadFile('/data/adb/modules/ZDT-D/working_folder/ip_ranges4.txt')"
                data-i18n="edit"></button>
      </div>
    </div>

    
    <!-- Блок дополнительных настроек -->
    <div class="bg-surface-container mb-4 p-4 rounded-lg">
      <h2 class="font-semibold mb-4 text-lg" data-i18n="additionalSettingsTitle"></h2>
      
      <!-- Блок "Выкл/Вкл сервис" оставляем без изменений -->
      <div class="flex items-center justify-between py-2">
        <span class="text-on-surface" data-i18n="toggleService"></span>
        <label class="relative inline-flex cursor-pointer items-center">
          <input type="checkbox" id="offonserviceToggle" class="peer sr-only" onchange="handleToggle(this.checked)" checked>
          <div class="peer h-6 w-11 rounded-full border border-outline bg-surface-variant
            after:absolute after:left-[2px] after:top-0.5 after:h-5 after:w-5 after:rounded-full 
            after:border after:border-4 after:border-surface-variant after:bg-outline 
            after:transition-all after:content-[''] 
            peer-checked:border-primary peer-checked:bg-primary 
            peer-checked:after:translate-x-full peer-checked:after:border-on-primary 
            peer-checked:after:bg-on-primary"></div>
        </label>
      </div>
      
      <!-- Ранее удалённые блоки (VPN и новая конфигурация zapret) остаются удалёнными -->
      
      <!-- Остальные настройки остаются без изменений -->
      <div class="flex items-center justify-between py-2">
        <span class="text-on-surface" data-i18n="toggleNotifications"></span>
        <label class="relative inline-flex cursor-pointer items-center">
          <input type="checkbox" id="notificationToggle" class="peer sr-only" onchange="saveSettings()">
          <div class="peer h-6 w-11 rounded-full border border-outline bg-surface-variant
            after:absolute after:left-[2px] after:top-0.5 after:h-5 after:w-5 after:rounded-full 
            after:border after:border-4 after:border-surface-variant after:bg-outline 
            after:transition-all after:content-[''] 
            peer-checked:border-primary peer-checked:bg-primary 
            peer-checked:after:translate-x-full peer-checked:after:border-on-primary 
            peer-checked:after:bg-on-primary"></div>
        </label>
      </div>
      
      <div class="flex items-center justify-between py-2">
        <span class="text-on-surface" data-i18n="alternativeConnection"></span>
        <label class="relative inline-flex cursor-pointer items-center">
          <!-- Здесь вызываем handleAlternativeToggle для управления доступностью DPI Tunnel -->
          <input type="checkbox" id="alternativelToggle" class="peer sr-only" onchange="handleAlternativeToggle(this.checked)">
          <div class="peer h-6 w-11 rounded-full border border-outline bg-surface-variant
            after:absolute after:left-[2px] after:top-0.5 after:h-5 after:w-5 after:rounded-full 
            after:border after:border-4 after:border-surface-variant after:bg-outline 
            after:transition-all after:content-[''] 
            peer-checked:border-primary peer-checked:bg-primary 
            peer-checked:after:translate-x-full peer-checked:after:border-on-primary 
            peer-checked:after:bg-on-primary"></div>
        </label>
      </div>
      
      <div class="flex items-center justify-between py-2">
        <span class="text-on-surface" data-i18n="enableDNS"></span>
        <label class="relative inline-flex cursor-pointer items-center">
          <input type="checkbox" id="dnsToggle" class="peer sr-only" onchange="handleDnsToggle(this.checked)">
          <div class="peer h-6 w-11 rounded-full border border-outline bg-surface-variant
            after:absolute after:left-[2px] after:top-0.5 after:h-5 after:w-5 after:rounded-full 
            after:border after:border-4 after:border-surface-variant after:bg-outline 
            after:transition-all after:content-[''] 
            peer-checked:border-primary peer-checked:bg-primary 
            peer-checked:after:translate-x-full peer-checked:after:border-on-primary 
            peer-checked:after:bg-on-primary"></div>
        </label>
      </div>
      
      <div class="flex items-center justify-between py-2">
        <span class="text-on-surface" data-i18n="applyZapretSystemwide"></span>
        <label class="relative inline-flex cursor-pointer items-center">
          <input type="checkbox" id="fullSystemToggle" class="peer sr-only" onchange="saveSettings()">
          <div class="peer h-6 w-11 rounded-full border border-outline bg-surface-variant
            after:absolute after:left-[2px] after:top-0.5 after:h-5 after:w-5 after:rounded-full 
            after:border after:border-4 after:border-surface-variant after:bg-outline 
            after:transition-all after:content-[''] 
            peer-checked:border-primary peer-checked:bg-primary 
            peer-checked:after:translate-x-full peer-checked:after:border-on-primary 
            peer-checked:after:bg-on-primary"></div>
        </label>
      </div>
      
      <div class="flex items-center justify-between py-2">
        <span class="text-on-surface" data-i18n="enableDpiTunnel0"></span>
        <label class="relative inline-flex cursor-pointer items-center">
          <input type="checkbox" id="dpiTunnel0Toggle" class="peer sr-only" onchange="saveSettings()">
          <div class="peer h-6 w-11 rounded-full border border-outline bg-surface-variant
            after:absolute after:left-[2px] after:top-0.5 after:h-5 after:w-5 after:rounded-full 
            after:border after:border-4 after:border-surface-variant after:bg-outline 
            after:transition-all after:content-[''] 
            peer-checked:border-primary peer-checked:bg-primary 
            peer-checked:after:translate-x-full peer-checked:after:border-on-primary 
            peer-checked:after:bg-on-primary"></div>
        </label>
      </div>
      
      <div class="flex items-center justify-between py-2">
        <span class="text-on-surface" data-i18n="enableDpiTunnel1"></span>
        <label class="relative inline-flex cursor-pointer items-center">
          <input type="checkbox" id="dpiTunnel1Toggle" class="peer sr-only" onchange="saveSettings()">
          <div class="peer h-6 w-11 rounded-full border border-outline bg-surface-variant
            after:absolute after:left-[2px] after:top-0.5 after:h-5 after:w-5 after:rounded-full 
            after:border after:border-4 after:border-surface-variant after:bg-outline 
            after:transition-all after:content-[''] 
            peer-checked:border-primary peer-checked:bg-primary 
            peer-checked:after:translate-x-full peer-checked:after:border-on-primary 
            peer-checked:after:bg-on-primary"></div>
        </label>
      </div>
    </div>
    
    <!-- Модальное окно для редактирования файлов -->
    <div id="fileModal" class="flex">
      <div class="modal-content">
        <h3 class="text-on-surface" data-i18n="editFile"></h3>
        <textarea id="fileInput"></textarea>
        <div class="text-right mt-2">
          <button class="btn btn-secondary" onclick="closeFileModal()" data-i18n="cancel"></button>
          <button class="btn btn-primary" onclick="saveFile()" data-i18n="save"></button>
        </div>
      </div>
    </div>
    
    <footer class="text-center py-4">
      <p class="text-on-surface-variant text-xs" data-i18n="developedBy"></p>
    </footer>
  </main>
  
  <!-- Добавляем кнопку "Поддержать автора"
       Атрибут onclick открывает ссылку в новом окне.
       Inline-стиль display: none; обеспечивает скрытие кнопки до применения перевода -->
  <button class="support-author" style="display: none;" onclick="window.open('https://yoomoney.ru/to/4100118340691506/0', '_blank')">Поддержать автора</button>
  
  <!-- Скрипты для работы с настройками и файлами -->
  <script>
    function handleToggle(state) {
      toggleService(state);
      saveSettings();
    }
    function handlevpnToggle(state) {
      togglevpnService(state);
      saveSettings();
    }
    function handleDnsToggle(state) {
      toggleDnsService(state);
      saveSettings();
    }
    
    // Новая функция для управления альтернативным подключением
    function handleAlternativeToggle(state) {
      // Если альтернативное подключение включено, делаем DPI Tunnel переключатели неактивными
      document.getElementById('dpiTunnel0Toggle').disabled = state;
      document.getElementById('dpiTunnel1Toggle').disabled = state;
      document.getElementById('fullSystemToggle').disabled = state;
      // Если переключатель включен – сбрасываем состояние DPI Tunnel
      if (state) {
        document.getElementById('dpiTunnel0Toggle').checked = false;
        document.getElementById('dpiTunnel1Toggle').checked = false;
        document.getElementById('fullSystemToggle').checked = false;
      }
      saveSettings();
    }
    
    function toggleService(state) {
      console.log('toggleService:', state);
      fetch('toggle_service.php', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ state })
      })
      .then(r => r.json())
      .then(data => console.log("Service toggled:", data))
      .catch(err => console.error(err));
    }
    function togglevpnService(state) {
      console.log('togglevpnService:', state);
      fetch('toggle_vpn_service.php', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ state })
      })
      .then(r => r.json())
      .then(data => console.log("VPN toggled:", data))
      .catch(err => console.error(err));
    }
    function toggleDnsService(state) {
      console.log('toggleDnsService:', state);
      fetch('toggle_dns_service.php', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ state })
      })
      .then(r => r.json())
      .then(data => console.log("DNS toggled:", data))
      .catch(err => console.error(err));
    }
    
    async function saveSettings() {
      const alternativel = document.getElementById('alternativelToggle').checked ? 1 : 0;
      const dnsEnabled = document.getElementById('dnsToggle').checked ? 1 : 0;
      const fullSystem = document.getElementById('fullSystemToggle').checked ? 1 : 0;
      const dpiTunnel0 = document.getElementById('dpiTunnel0Toggle').checked ? 1 : 0;
      const dpiTunnel1 = document.getElementById('dpiTunnel1Toggle').checked ? 1 : 0;
      const offonservice = document.getElementById('offonserviceToggle').checked ? 1 : 0;
      const notification = document.getElementById('notificationToggle').checked ? 1 : 0;
      const vpn_service = document.getElementById('vpn_serviceToggle') ? (document.getElementById('vpn_serviceToggle').checked ? 1 : 0) : 0;
      const zapretconfig = document.getElementById('zapretconfigToggle') ? (document.getElementById('zapretconfigToggle').checked ? 1 : 0) : 0;
      
      const settings = `dns=${dnsEnabled}\nfull_system=${fullSystem}\ndpi_tunnel_0=${dpiTunnel0}\ndpi_tunnel_1=${dpiTunnel1}\nalternativel=${alternativel}\nnotification=${notification}\nvpn_service=${vpn_service}\nzapretconfig=${zapretconfig}\noffonservice=${offonservice}`;
      try {
        const response = await fetch('save_file.php', {
          method: 'POST',
          headers: {'Content-Type': 'application/x-www-form-urlencoded'},
          body: `filepath=/data/adb/modules/ZDT-D/working_folder/params&content=${encodeURIComponent(settings)}`
        });
        const data = await response.json();
        console.log("Settings saved:", data);
      } catch (err) {
        console.error(err);
      }
    }
    
    let currentFile = '';
    
    function loadFile(filepath) {
      currentFile = filepath;
      fetch('load_file.php', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: `filepath=${encodeURIComponent(filepath)}`
      })
      .then(response => response.text())
      .then(data => {
        document.getElementById('fileInput').value = data;
        document.getElementById('fileModal').style.display = 'flex';
      })
      .catch(err => console.error("Ошибка при загрузке файла:", err));
    }
    
    function closeFileModal() {
      document.getElementById('fileModal').style.display = 'none';
    }
    
    function saveFile() {
      const content = document.getElementById('fileInput').value;
      fetch('save_file.php', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: `filepath=${encodeURIComponent(currentFile)}&content=${encodeURIComponent(content)}`
      })
      .then(() => {
        closeFileModal();
        console.log("File saved");
      })
      .catch(err => console.error("Ошибка при сохранении файла:", err));
    }
    
    async function loadSettings() {
      try {
        const response = await fetch('load_file.php', {
          method: 'POST',
          headers: {'Content-Type': 'application/x-www-form-urlencoded'},
          body: `filepath=${encodeURIComponent('/data/adb/modules/ZDT-D/working_folder/params')}`
        });
        const data = await response.text();
        const settings = parseSettings(data);
        document.getElementById('alternativelToggle').checked = settings.alternativel || false;
        // При загрузке настроек, если альтернативное подключение включено – отключаем DPI переключатели
        document.getElementById('dpiTunnel0Toggle').disabled = settings.alternativel;
        document.getElementById('dpiTunnel1Toggle').disabled = settings.alternativel;
        document.getElementById('dnsToggle').checked = settings.dns || false;
        document.getElementById('fullSystemToggle').checked = settings.full_system || false;
        document.getElementById('dpiTunnel0Toggle').checked = settings.dpi_tunnel_0 || false;
        document.getElementById('dpiTunnel1Toggle').checked = settings.dpi_tunnel_1 || false;
        document.getElementById('offonserviceToggle').checked = settings.offonservice || false;
        document.getElementById('notificationToggle').checked = settings.notification || false;
        if(document.getElementById('vpn_serviceToggle')){
          document.getElementById('vpn_serviceToggle').checked = settings.vpn_service || false;
        }
        if(document.getElementById('zapretconfigToggle')){
          document.getElementById('zapretconfigToggle').checked = settings.zapretconfig || false;
        }
      } catch (err) {
        console.error(err);
      }
    }
    
    function parseSettings(str) {
      const obj = {};
      str.split('\n').forEach(line => {
        const [key, value] = line.split('=');
        if (key && value) obj[key.trim()] = value.trim() === '1';
      });
      return obj;
    }
    
    window.onload = loadSettings;
  </script>
  
  <!-- Скрипт для перевода страницы -->
  <script>
    const translations = {
      en: {
        title: "ZDT-D",
        infoTitle: "Information (recommended reading)",
        readButton: "Read",
        settingsTitle: "Settings",
        byedpiconfig: "Configurations Bye DPI",
        byedpilist: "Bye DPI Application List",
        configDpiTunnel0: "DPI Tunnel 0 Configuration",
        configDpiTunnel1: "DPI Tunnel 1 Configuration",
        listAppsDpiTunnel0: "DPI Tunnel 0 Application List",
        listAppsDpiTunnel1: "DPI Tunnel 1 Application List",
        configZapret0: "Zapret 0 Configuration",
        configZapret1: "Zapret 1 Configuration",
        configZapret2: "Zapret 2 Configuration",
        configZapret3: "Zapret 3 Configuration",
        configZapret4: "Zapret 4 Configuration",
        listAppsZapret0: "Zapret 0 Application List",
        listAppsZapret1: "Zapret 1 Application List",
        listAppsZapret2: "Zapret 2 Application List",
        listAppsZapret3: "Zapret 3 Application List",
        listAppsZapret4: "Zapret 4 Application List",
        listAppsZapret5: "Zapret 5 Application List aggressive",
        listIp3: "IP List zapret 3",
        listIp4: "IP List zapret 4",
        additionalSettingsTitle: "Additional Settings",
        toggleService: "Toggle Service",
        toggleVpn: "Start VPN VLESS (in development)",
        useNewZapretConfig: "Use new Zapret configuration",
        toggleNotifications: "Toggle Notifications",
        alternativeConnection: "Alternative Connection Method",
        enableDNS: "Enable DNS from AdGuard, Cloudflare, and Google",
        applyZapretSystemwide: "Apply Zapret systemwide",
        enableDpiTunnel0: "Enable DPI Tunnel 0",
        enableDpiTunnel1: "Enable DPI Tunnel 1",
        editFile: "Edit File",
        cancel: "Cancel",
        save: "Save",
        edit: "Edit",
        developedBy: "Developed by Ggover",
        loading: "Loading",
        oneMoment: "One moment please...",
        moduleTitle: "ZDT-D",
        breadcrumbsEncore: "ZDT-D"
      },
      ru: {
        title: "ZDT-D",
        infoTitle: "Информация, рекомендуется к прочтению",
        readButton: "Читать",
        settingsTitle: "Настройки",
        byedpiconfig: "Конфигурация Bye DPI",
        byedpilist: "Список приложений Bye DPI",
        configDpiTunnel0: "Конфигурация DPI Tunnel 0",
        configDpiTunnel1: "Конфигурация DPI Tunnel 1",
        listAppsDpiTunnel0: "Список приложений DPI Tunnel 0",
        listAppsDpiTunnel1: "Список приложений DPI Tunnel 1",
        configZapret0: "Конфигурация Zapret 0",
        configZapret1: "Конфигурация Zapret 1",
        configZapret2: "Конфигурация Zapret 2",
        configZapret3: "Конфигурация Zapret 3",
        configZapret4: "Конфигурация Zapret 4",
        listAppsZapret0: "Список приложений zapret 0",
        listAppsZapret1: "Список приложений zapret 1",
        listAppsZapret2: "Список приложений zapret 2",
        listAppsZapret3: "Список приложений zapret 3",
        listAppsZapret4: "Список приложений zapret 4",
        listAppsZapret5: "Список приложений zapret 5 агрессивный",
        listIp3: "Список ip zapret 3",
        listIp4: "Список ip zapret 4",
        additionalSettingsTitle: "Дополнительные настройки",
        toggleService: "Выкл/Вкл сервис",
        toggleVpn: "Запустить VPN VLESS (в разработке)",
        useNewZapretConfig: "Использовать новую конфигурацию zapret",
        toggleNotifications: "Выкл/Вкл уведомления",
        alternativeConnection: "Альтернативный способ подключения",
        enableDNS: "Включить DNS от AdGuard, Cloudflare и Google",
        applyZapretSystemwide: "Применить zapret по всей системе",
        enableDpiTunnel0: "Включить DPI Tunnel 0",
        enableDpiTunnel1: "Включить DPI Tunnel 1",
        editFile: "Редактировать файл",
        cancel: "Отмена",
        save: "Сохранить",
        edit: "Редактировать",
        developedBy: "Разработано Ggover",
        loading: "Загрузка",
        oneMoment: "Один момент, пожалуйста...",
        moduleTitle: "ZDT-D",
        breadcrumbsEncore: "ZDT-D"
      }
    };

    function translatePage(lang) {
      document.documentElement.lang = lang;
      document.querySelectorAll('[data-i18n]').forEach(el => {
        const key = el.getAttribute('data-i18n');
        if (translations[lang] && translations[lang][key]) {
          el.innerText = translations[lang][key];
        }
      });
      // Обновляем заголовок страницы
      document.title = translations[lang].title;
      // Кнопка "Поддержать автора" отображается только для русского языка
      const supportButton = document.querySelector('.support-author');
      if (supportButton) {
        supportButton.style.display = (lang === "ru" ? "block" : "none");
      }
    }
    
    let defaultLang = 'en';
    if (navigator.language && navigator.language.startsWith('ru')) {
      defaultLang = 'ru';
    }
    
    const languageSelector = document.getElementById('languageSelector');
    languageSelector.value = defaultLang;
    translatePage(defaultLang);
    
    languageSelector.addEventListener('change', (e) => {
      translatePage(e.target.value);
    });
  </script>
  
  <!-- Скрипт для плавного появления страницы -->
  <script>
    window.addEventListener('load', () => {
      document.body.classList.add('fade-in');
    });
  </script>
  
</body>
</html>
