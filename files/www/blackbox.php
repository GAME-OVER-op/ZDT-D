<?php

require_once '/data/adb/modules/ZDT-D/php7/files/www/auth/auth_functions.php';

// If login is disabled, set the current page but do not redirect to login
if (isset($_SESSION['login_disabled']) && $_SESSION['login_disabled'] === true) {
    // Login is disabled, handle accordingly
    // You can show a message or just let the user stay on the page
    //echo "<p>Login is currently disabled.</p>";
} else {
    // Proceed to check if the user is logged in
    checkUserLogin();
}

$clashlogs = "/data/adb/box/run/runs.log";
$pid = "/data/adb/box/run/box.pid";
$moduledir = "../modules/box_for_magisk";

if ($_SERVER["REQUEST_METHOD"] == "POST") {
    $action = $_REQUEST['actionButton'];
    switch ($action) {
        case "disable":
            $myfile = fopen("$moduledir/disable", "w") or die("Unable to open file!");
            break;
        case "enable":
            unlink("$moduledir/disable");
            break;
    }
}

$p = $_SERVER['HTTP_HOST'];
$x = explode(':', $p);
$host = $x[0];
?>

<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>BLACK BOX</title>
  <style>
    * {
      box-sizing: border-box;
    }

    body {
      font-family: "Open Sans", sans-serif;
      background: #374954;
      color: white;
      text-align: center;
      margin: 0;
      padding: 0;
    }
    .logo {
      text-align: center;
      margin: 10px auto;
    }
    .logo img {
      width: 200px; /* Adjust width to make the logo smaller */
      height: auto;
    }
    #main {
      position: relative;
      list-style: none;
      background: #374954;
      font-weight: 400;
      font-size: 0;
      text-transform: uppercase;
      display: inline-block;
      padding: 0;
      margin: 1px auto;
      height: 55px; /* Adjust height to match tab height */
    }

    #main li {
      font-size: 0.8rem;
      display: inline-block;
      position: relative;
      padding: 15px 20px;
      cursor: pointer;
      z-index: 5;
      min-width: 120px;
      height: 100%; /* Make sure li items take full height */
      line-height: 32px; /* Vertically center text in the li items */
      margin: 0;
    }

    .drop {
      overflow: hidden;
      list-style: none;
      position: absolute;
      padding: 0;
      width: 100%;
      left: 0;
      top: 60px; /* Position below the nav bar */
    }

    .drop div {
      -webkit-transform: translate(0, -100%);
      -moz-transform: translate(0, -100%);
      -ms-transform: translate(0, -100%);
      transform: translate(0, -100%);
      -webkit-transition: all 0.5s 0.1s;
      -moz-transition: all 0.5s 0.1s;
      -ms-transition: all 0.5s 0.1s;
      transition: all 0.5s 0.1s;
      position: relative;
    }

    .drop li {
      display: block;
      padding: 0;
      width: 100%;
      background: #374954 !important;
    }

    #marker {
      height: 6px;
      background: #3E8760 !important;
      position: absolute;
      bottom: 0;
      width: 120px;
      z-index: 2;
      -webkit-transition: all 0.35s;
      -moz-transition: all 0.35s;
      -ms-transition: all 0.35s;
      transition: all 0.35s;
    }

    #main li:nth-child(1):hover ul div {
      -webkit-transform: translate(0, 0);
      -moz-transform: translate(0, 0);
      -ms-transform: translate(0, 0);
      transform: translate(0, 0);
    }

    #main li:nth-child(1):hover ~ #marker {
      -webkit-transform: translate(0px, 0);
      -moz-transform: translate(0px, 0);
      -ms-transform: translate(0px, 0);
      transform: translate(0px, 0);
    }

    #main li:nth-child(2):hover ul div {
      -webkit-transform: translate(0, 0);
      -moz-transform: translate(0, 0);
      -ms-transform: translate(0, 0);
      transform: translate(0, 0);
    }

    #main li:nth-child(2):hover ~ #marker {
      -webkit-transform: translate(120px, 0);
      -moz-transform: translate(120px, 0);
      -ms-transform: translate(120px, 0);
      transform: translate(120px, 0);
    }

    #main li:nth-child(3):hover ul div {
      -webkit-transform: translate(0, 0);
      -moz-transform: translate(0, 0);
      -ms-transform: translate(0, 0);
      transform: translate(0, 0);
    }

    #main li:nth-child(3):hover ~ #marker {
      -webkit-transform: translate(240px, 0);
      -moz-transform: translate(240px, 0);
      -ms-transform: translate(240px, 0);
      transform: translate(240px, 0);
    }

    #main li:nth-child(4):hover ul div {
      -webkit-transform: translate(0, 0);
      -moz-transform: translate(0, 0);
      -ms-transform: translate(0, 0);
      transform: translate(0, 0);
    }

    #main li:nth-child(4):hover ~ #marker {
      -webkit-transform: translate(360px, 0);
      -moz-transform: translate(360px, 0);
      -ms-transform: translate(360px, 0);
      transform: translate(360px, 0);
    }

    .tab-content {
      display: none;
      width: 100%;
      
    }

    .tab-content iframe {
      width: 100%;
      height: calc(100vh - 60px); /* Adjust based on header/footer height */
      border: none;
    }
  </style>
</head>
<body>
    <div class="logo">
        <img src="webui/assets/img/logo.png" alt="Logo">
    </div>

    <ul id="main">
      <li onclick="showIframe('service')">Service</li>
      <li onclick="showIframe('logs')">Logs</li>
      <li onclick="showIframe('box-set')">BOX Set</li>
      <li onclick="toggleSubmenu()">Config
        <ul class="drop">
          <div id="config">
            <li onclick="showIframe('clash')">Clash</li>
            <li onclick="showIframe('sing-box')">Sing-Box</li>
          </div>
        </ul>
      </li>
      <li onclick="showIframe('tiny')">TinyFM</li>
      <div id="marker"></div>
    </ul>

  
  <div id="service" class="tab-content">
    <iframe src="/tools/executed.php"></iframe>
  </div>
  <div id="logs" class="tab-content">
    <iframe src="/tools/logs.php"></iframe>
  </div>
  <div id="box-set" class="tab-content">
    <iframe src="/tools/boxsettings.php"></iframe>
  </div>
  <div id="tiny" class="tab-content">
    <iframe src="/tiny"></iframe>
  </div>
  <div id="clash" class="tab-content">
    <iframe src="http://<?php echo $host; ?>/tiny/index.php?p=box%2Fclash&edit=config.yaml&env=ace"></iframe>
  </div>
  <div id="sing-box" class="tab-content">
    <iframe src="http://<?php echo $host; ?>/tiny/index.php?p=box%2Fsing-box&edit=config.json&env=ace"></iframe>
  </div>


  <script>
    function showIframe(id) {
      // Hide all tab content
      document.querySelectorAll('.tab-content').forEach(function(content) {
        content.style.display = 'none';
      });
      
      // Show selected tab content
      document.getElementById(id).style.display = 'block';
      
      // Adjust the marker position
      var tabs = document.querySelectorAll('#main li');
      tabs.forEach(function(tab, index) {
        if (tab.innerText === document.querySelector('#' + id).previousElementSibling.innerText) {
          document.getElementById('marker').style.transform = 'translateX(' + (index * 120) + 'px)';
        }
      });
    }

    function toggleSubmenu() {
      const submenu = document.querySelector('#config .drop');
      if (submenu.style.display === 'block') {
        submenu.style.display = 'none';
      } else {
        submenu.style.display = 'block';
      }
    }

    // Show the 'Service' tab content on page load
    document.addEventListener("DOMContentLoaded", function() {
      showIframe('service');
    });
  </script>
</body>
</html>
