<?php
$clashlogs = "/data/adb/box/run/runs.log";
$pid = "/data/adb/box/run/box.pid";
$moduledir = "../modules/box_for_magisk";

session_start();

if (!isset($_SESSION['user_id'])) {
    header('Location: /auth/login.php');
    exit;
}

if ($_SERVER["REQUEST_METHOD"] == "POST") {
    $action = $_REQUEST['actionButton'];
    switch ($action) {
        case "disable":
            $myfile = fopen("$moduledir/disable", "w") or die("Unable to open file!");
            break;
        case "enable":
            unlink("$moduledir/disable");
            break;
        case "reboot":
            shell_exec("su -c reboot");
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
    <style>
        /* iframe style */
        .dashboard-iframe {
            width: 100%;
            height: calc(100vh - 130px);
            border: none;
        }
        /* Custom popup styles */
        .popup {
            display: none;
            position: fixed;
            left: 50%;
            top: 50%;
            transform: translate(-50%, -50%);
            background-color: #fff;
            padding: 20px;
            box-shadow: 0 0 10px rgba(0,0,0,0.1);
            z-index: 1000;
            border-radius: 12px;
            max-width: 300px;
            text-align: center;
        }
        .popup .popup-content {
            margin-bottom: 20px;
            font-size: 16px;
        }
        .popup .popup-buttons {
            display: flex;
            justify-content: center;
            gap: 10px;
        }
        .popup .popup-buttons button {
            padding: 10px 20px;
            border: none;
            border-radius: 12px;
            color: #fff;
            cursor: pointer;
        }
        .popup .popup-buttons .yes-button {
            background-color: #4CAF50;
        }
        .popup .popup-buttons .no-button {
            background-color: #f44336;
        }
        /* Overlay for popup */
        .overlay {
            display: none;
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            background: rgba(0, 0, 0, 0.5);
            z-index: 999;
        }
        /* Dark mode text color */
        body.dark-mode .dash-content, body.dark-mode .nav-links a {
            color: white;
        }
        /* Submenu styles */
        .submenu {
            display: none;
            list-style-type: none;
            padding-left: 20px;
        }
        .submenu li {
            margin-top: 10px;
        }
    </style>
    <meta charset="UTF-8">
    <meta http-equiv="X-UA-Compatible" content="IE=edge">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    
    <!-- CSS -->
    <link rel="stylesheet" href="/webui/custom/style.css">
    <link rel="stylesheet" href="https://unicons.iconscout.com/release/v4.0.0/css/line.css">

    <title>Admin Dashboard Panel</title> 

</head>
<body>
<h2><?php echo htmlspecialchars($_SESSION['username']); ?>!</h2>
    <nav>
        <div class="logo-name">
            <div class="logo-image">
                <img src="/webui/custom/Images/logo.png" alt="">
            </div>
            <span class="logo_name">BFM DROID Webui</span>
        </div>

        <div class="menu-items">
            <ul class="nav-links">
                <li><a href="#" onclick="loadIframe('/index.html')">
                    <i class="uil uil-estate"></i>
                    <span class="link-name">Dashboard</span>
                </a></li>

                <li><a href="#" onclick="loadIframe('/tools/file.php')">
                    <i class="uil uil-folder"></i>
                    <span class="link-name">Files</span>
                </a></li>
                <li><a href="#" onclick="loadIframe('/tools/smsviewer.php')">
                    <i class="uil uil-message"></i>
                    <span class="link-name">SMS Viewer</span>
                </a></li>
                <li><a href="#" onclick="loadIframe('http://<?php echo $host; ?>:3001')">
                    <i class="uil uil-server"></i>
                    <span class="link-name">Terminal ttyd</span>
                </a></li>
                <li>
                    <a href="#" onclick="toggleSubmenu('clashSubmenu')">
                        <i class="uil uil-server"></i>
                        <span class="link-name">Clash</span>
                    </a>
                    <ul class="submenu" id="clashSubmenu">

                        <li><a href="#" onclick="loadIframe('http://<?php echo $host; ?>:9090/ui/#/proxies')">
                            <i class="uil uil-file-network"></i>
                            <span class="link-name">____YACD</span>
                        </a></li>
                        <li><a href="#" onclick="loadIframe('http://<?php echo $host; ?>/tools/file.php?p=box%2Fclash&edit=config.yaml&env=ace')">
                            <i class="uil uil-brackets-curly"></i>
                            <span class="link-name">____editor</span>
                        </a></li>
                        <li><a href="#" onclick="loadIframe('/tools/executed.php')">
                            <i class="uil uil-server"></i>
                            <span class="link-name">____Command</span>
                        </a></li>
                        <li><a href="#" onclick="loadIframe('/tools/ocgen/index.php')">
                            <i class="uil uil-server"></i>
                            <span class="link-name">____Generator</span>
                        </a></li>
                        <li><a href="#" onclick="loadLogs()">
                            <i class="uil uil-file-alt"></i>
                            <span class="link-name">____Clash Logs</span>
                        </a></li>
                    </ul>
                </li>
                <li><a href="#" onclick="loadIframe('/tools/sysinfo.php')">
                    <i class="uil uil-folder-info"></i>
                    <span class="link-name">System Info</span>
                </a></li>
                <li><a href="#">
                    <i class="uil uil-telegram"></i>
                    <span class="link-name">Telegram</span>
                </a></li>
            </ul>
            
            <ul class="logout-mode">
                <li><a href="#" onclick="showRebootPopup()">
                    <i class="uil uil-refresh"></i>
                    <span class="link-name">Reboot</span>
                </a></li>
                <li><a href="/auth/logout.php">
                    <i class="uil uil-sign-out-alt"></i>
                    <span class="link-name">LogOut</span>
                </a></li>
                <li><a href="#" onclick="loadIframe('/auth/change_password.php')">
                    <i class="uil uil-lock"></i>
                    <span class="link-name">Change Password</span>
                </a></li>
                <li class="mode">
                    <a href="#">
                        <i class="uil uil-moon"></i>
                        <span class="link-name">Dark Mode</span>
                    </a>
                    <div class="mode-toggle">
                        <span class="switch"></span>
                    </div>
                </li>
            </ul>
        </div>
    </nav>

    <section class="dashboard">
        <div class="top">
            <i class="uil uil-bars sidebar-toggle"></i>

            <div class="search-box">
                <i class="uil uil-search"></i>
                <input type="text" placeholder="Search here..." onkeyup="filterMenu(this.value)">
            </div>
            
            <img src="/webui/custom/Images/profile.jpg" alt="">
        </div>

        <div id="content" class="dash-content">
            <!-- Iframe content will be loaded dynamically here -->
            <iframe id="iframeContent" class="dashboard-iframe" src="/index.html" frameborder="0"></iframe>
        </div>
    </section>

    <!-- Reboot confirmation popup -->
    <div class="overlay" id="overlay"></div>
    <div class="popup" id="rebootPopup">
        <div class="popup-content">Are you sure you want to reboot?</div>
        <div class="popup-buttons">
            <button class="no-button" onclick="cancelReboot()">No</button>
            <button class="yes-button" onclick="confirmReboot()">Yes</button>
        </div>
    </div>

    <!-- JavaScript for Sidebar Toggle, Iframe Loading, and Search Filter -->
    <script>
        const iframeContent = document.getElementById('iframeContent');
        const activeTabKey = 'activeTab';

        // Function to load iframe and store active tab
        function loadIframe(url) {
            iframeContent.src = url;
            localStorage.setItem(activeTabKey, url); // Store active tab URL
        }

        // Function to load logs and store active tab
        function loadLogs() {
            const url = '/tools/logs.php';
            iframeContent.src = url;
            localStorage.setItem(activeTabKey, url); // Store active tab URL
        }

        // Function to retrieve and set active tab on page load
        document.addEventListener('DOMContentLoaded', () => {
            const activeTab = localStorage.getItem(activeTabKey);
            if (activeTab) {
                iframeContent.src = activeTab; // Load stored active tab URL
            } else {
                iframeContent.src = '/index.html'; // Default to dashboard if no stored tab found
            }
        });

        // Functions for the reboot popup
        function showRebootPopup() {
            document.getElementById('rebootPopup').style.display = 'block';
            document.getElementById('overlay').style.display = 'block';
        }

        function cancelReboot() {
            document.getElementById('rebootPopup').style.display = 'none';
            document.getElementById('overlay').style.display = 'none';
        }

        function confirmReboot() {
            // Send a POST request to execute the reboot command
            fetch(window.location.href, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                },
                body: 'actionButton=reboot'
            }).then(() => {
                alert('Rebooting...');
            });
        }

        // Function to filter the menu list based on search input
        function filterMenu(query) {
            const items = document.querySelectorAll('.nav-links > li, .submenu > li');
            const lowerQuery = query.toLowerCase();
            items.forEach(item => {
                const text = item.textContent.toLowerCase();
                item.style.display = text.includes(lowerQuery) ? '' : 'none';
            });
        }

        // Function to toggle submenu visibility
        function toggleSubmenu(submenuId) {
            const submenu = document.getElementById(submenuId);
            submenu.style.display = submenu.style.display === 'block' ? 'none' : 'block';
        }
    </script>
    <script src="/webui/custom/script.js"></script>
</body>
</html>
