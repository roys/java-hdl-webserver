# Web server for HDL Buspro
**This is a web server for setting up and communicating with the smart home system *HDL Buspro*. It was mainly built for
the purpose of integrating with *Google Assistant* (Google Home and Google Pixel).**

## Table of Contents
- [Video demo](#video-demo)
- [How it works](#how-it-works)
- [Example commands](#example-commands)
- [Running the server - quick and dirty](#running-the-server---quick-and-dirty)
- [Running the server - complete setup on a Raspberry Pi](#running-the-server---complete-setup-on-a-raspberry-pi)
- [Upgrading to version 2](#upgrading-to-version-2)
- [Security](#security)
- [Building from source](#building-from-source)
- [Links](#links)

## Video demo
[![YouTube proof of concept video](http://img.youtube.com/vi/P6BgsM8IiW8/0.jpg)](https://youtu.be/P6BgsM8IiW8)

## How it works
Google have added some [triggers at IFTTT](https://ifttt.com/google_assistant) for Google Assistant (i.e. Google Home
and Google Pixel). Combining them with the [Webhooks actions](https://ifttt.com/maker_webhooks), a web request can be sent to the
HDL Buspro server in this project. The server interprets the requests by using the actions you have set up and sends the
belonging HDL commands to the IP gateway.

## Example commands
 - *Dim the lights to [X]% in [area/room]*
 - *Set the lights to [X]% in [area/room]*
 - *Turn [on/off] the lights in [area/room]*
 - *Turn [on/off] the TV*
 - *[Open/close] the garage*
 - *Bye bye*
 - *Turn [on/off] the alarm*
 - *Send for help*

## Running the server - quick and dirty
Prerequisites:
 - Java 17 or later (e.g. [Eclipse Temurin](https://adoptium.net/))
 - [The server JAR](https://github.com/roys/java-hdl-webserver/raw/master/releases/hdlserver-LATEST.jar)

### Un*x
To start the server on Linux, Mac and other Unix-like systems:  
`nohup java -cp hdlserver-LATEST.jar com.roysolberg.java.hdlserver.Application &`

The server will start logging to the `logs` directory. See that everything is running fine:
`tail -f logs/hdl-server.log`

### Windows
To start the server on Windows:  
`javaw -cp hdlserver-LATEST.jar com.roysolberg.java.hdlserver.Application`  
Alternatively:  
`start java -cp hdlserver-LATEST.jar com.roysolberg.java.hdlserver.Application`

See that everything is fine by looking in the file `logs\hdl-server.log`.

### Un*x + Windows
You should now be able to go to the server in a browser from a computer on the local network. The server is running on port 4567, so go to `http://<server ip address>:4567`.

Next step is to [create some actions](#part-2-adding-commands-actions).

## Running the server - complete setup on a Raspberry Pi

### Part 1: Download and set up the server

#### 1. Java setup

First SSH into your Raspberry Pi. Raspberry Pi OS 11 (Bullseye) or later can run Java 17.

Type `java -version` to see which Java version you have. If it says 17 or later you are good to go. Just continue to the next step.

Otherwise install Java 17:  
`sudo apt update`  
`sudo apt install openjdk-17-jre-headless`  
(On Raspberry Pi OS 13, `default-jre-headless` gives Java 21, which works too.)

On an older Raspberry Pi OS (or Raspbian), either upgrade it, or keep using [version 1.1.1](#upgrading-to-version-2), which runs on Java 8.

#### 2. Install Git

If you don't have the version-control system Git installed you can install it like this:  
`sudo apt install git`  

#### 3. Directory setup and getting the server

First check out the code:  
`cd ~`  
`git clone https://github.com/roys/java-hdl-webserver.git`  

It can be smart to check that the server is working and getting data:  
`cd java-hdl-webserver/releases`  
`java -cp hdlserver-LATEST.jar com.roysolberg.java.hdlserver.Application`

You should see some output confirming that the server is starting up ok. Please note that it could take a few seconds to start up on hardware like one of the Raspberry Pi models.

Use `ctrl + c` to exit the application.

#### 4. Running the server on startup

Run the server as a systemd service, so it starts when the Raspberry Pi does (once the network is up) and restarts if it ever stops. Paste this
as it is (it fills in your user name and home directory):
```
sudo tee /etc/systemd/system/hdlserver.service > /dev/null <<EOF
[Unit]
Description=HDL Buspro server
Wants=network-online.target
After=network-online.target

[Service]
User=$USER
WorkingDirectory=$HOME/java-hdl-webserver/releases
ExecStart=/usr/bin/java -cp hdlserver-LATEST.jar com.roysolberg.java.hdlserver.Application
Restart=on-failure
RestartSec=10
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=$HOME/java-hdl-webserver/releases

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload && sudo systemctl enable --now hdlserver
```

See that it's running:  
`systemctl status hdlserver`

(Earlier versions of this guide started the server with `@reboot ~/java-hdl-webserver/launcher.sh` in the crontab. That still works, but
remove it from the crontab if you switch to the service, so the server isn't started twice.)

#### 5. Reading the logs

You can easily tap in to what's going by running `tail` inside `~/java-hdl-webserver/releases`:  
`tail -f logs/hdl-bus.log`  
`tail -f logs/hdl-server.log`  

This will let you follow the file and the contents that's written to it. To end the session just press `ctrl + c`.

The server starts a new log file every day, and deletes log files older than 30 days. With the service, its output (including
any errors at startup) is also in `journalctl -u hdlserver`.

#### 6. Remote access

To be able to have IFTTT access (described later) the server and run the commands you set up you have to ensure that the server is accessible from the Internet. How to do this depends on your Internet router, but in general it will be like this:
 - Give the computer running the server a static IP address in your router
 - Forward a port (please use some other high number port than 4567) from Internet to port 4567 to the computer running the server
 - If you put a reverse proxy (e.g. for HTTPS) in front of the server, only pass on paths starting with `/api/`
 - Set up some kind of dynamic DNS (can often be done on the router itself, or you can use the computer running the server) to get a static hostname for IFTTT
 - Use the hostname and external port number when setting up IFTTT (described later)

Only the API needs to be reachable from the Internet. For anything else, like SSH to the Raspberry Pi, use a VPN (e.g.
[Tailscale](https://tailscale.com/)) rather than forwarding more ports.

#### 7. Up and running
You should now be able to go to the server in a browser from a computer on the local network. The server is running on port 4567, so go to `http://<server ip address>:4567`.

Next step is to [create some actions](#part-2-adding-commands-actions).

#### Updating the server

If you chose to do a `git clone` in the first step, run `./update.sh` inside `~/java-hdl-webserver`. It checks that the new version runs on
your Java before updating anything, and restarts the service from step 4.

Otherwise, replace `hdlserver-LATEST.jar` with [the latest one](https://github.com/roys/java-hdl-webserver/raw/master/releases/hdlserver-LATEST.jar)
and restart the server. New versions keep your authorization key, actions and found components (they're in `hdlserver.db`), so your IFTTT applets keep
working.

If you open the web pages by a host name with dots in it (e.g. `http://hdl.example.com:4567`) rather than by IP address or a local name like
`raspberrypi`/`raspberrypi.local`, start the server with that name allowed:  
`java -Dhdlserver.allowedHosts=hdl.example.com -cp hdlserver-LATEST.jar com.roysolberg.java.hdlserver.Application`

#### Upgrading to version 2

Version 2 needs **Java 17 or later** (version 1 ran on Java 8). Everything else stays the same: the start command, port 4567, the API your IFTTT
applets call, and `hdlserver.db` with your authorization key, actions and found components.

1. Check your Java with `java -version`. If it's older than 17, install a newer one first (see [Java setup](#1-java-setup)).
2. Update as before: `git pull` inside `~/java-hdl-webserver` (from then on, `./update.sh`), or replace the JAR.
3. Restart the server.

If the server is started on a Java older than 17, it doesn't start, and says why on the screen and in `logs/hdl-server.log`. To go back to
version 1.1.1, which runs on Java 8, run this inside `~/java-hdl-webserver`:  
`git checkout v1.1.1 -- releases/hdlserver-LATEST.jar`  
or download it from [the 1.1.1 release](https://github.com/roys/java-hdl-webserver/releases/tag/v1.1.1). Run `git checkout -- releases/hdlserver-LATEST.jar`
once you have Java 17, to get back to the latest version.

Small differences from version 1, should you call the server in other ways than the ones described here:
 - The API no longer requires an `Accept: application/json` header (version 1 answered 404 to other values).
 - The web pages' forms only read their fields from the request body, not from the URL.
 - Requests with an `X-HTTP-Method-Override` header are refused (400), and so are ambiguous paths like `/api/%2e%2e/settings`.

### Part 2: Adding commands (actions)

Prerequisites: An account at https://ifttt.com and a Google account.

#### Create dimmer actions

Dimmer actions let you say commands like *Dim the lights to 20% in the kitchen* and are meant for HDL dimmers.

<img align="right" src="https://github.com/roys/java-hdl-webserver/raw/master/misc/01-create_dimmer_action.png" alt="Example of creating dimmer action for HDL Buspro server" title="Example of creating dimmer action for HDL Buspro server" width="500" />

1. Go to to the **Actions** page.
2. Below **Create dimmer action**, type in the name of the area. This will be the area you asked to be dimmed, e.g. *The kitchen*.  
You can also add zero to many aliases for the area if you use different names for it. If using *"the"* in an area name you might want to repeat the name of the area without *"the"*. Then everything will work if you say *Dim the lights to 20% in kitchen*.
3. Select the dimmer channel representing the area.  
If you want the same area to control several areas or you have several channels for the same area you can add those as well.
4. Select *Create action* and notice the info about the action at the bottom of the page.
5. Go to https://ifttt.com/create to create a new "applet" (preferably in a new browser window/tab).
6. Select the service *Google Assistant*.
7. Select *Say a phrase with both a number and a text ingredient*.
8. Choose up to three commands you want to say to trigger the action, e.g.:
 - *Dim the lights to # % in $*
 - *Turn up the lights to # % in $*
 - *Set the lights to # % in $*
9. Choose a response from the Assistant. It can be nice to include the input from your command, e.g. *OK, # % in $*.
10. Create the trigger.
11. Select the action service *Webhooks*.
12. Select *Make a web request*.
13. Fill in the URL, method (POST), content type (application/json) and body as described in the **Actions** page in step 4.
14. Create the action.
15. Don't be to impatient, but you should now be able to test out the command you just created, e.g. *OK Google, dim the lights to 20% in the kitchen*.

#### Create custom actions

Custom actions let you give custom commands like *Open the garage* which can trigger some panel button or set a universal switch.

<img align="right" src="https://github.com/roys/java-hdl-webserver/raw/master/misc/02-create_custom_action.png" alt="Example of creating custom action for HDL Buspro server" title="Example of creating custom action for HDL Buspro server" width="500" />

1. Go to to the **Actions** page.
2. Below **Create custom action**, type in the name/description of the command.  
This command is just a description and doesn't have to be the same as the voice command.
3. Select the component, operation, function and parameters.  
**Panel control** in combination with **Control button** lets you specify the button number as parameters 2 and 1 for on and 0 for off.  
If you want the same command to do more commands you can add them.
4. Select *Create action* and notice the info about the action at the bottom of the page.  
5. Go to https://ifttt.com/create to create a new "applet" (preferably in a new browser window/tab).
6. Select the service *Google Assistant*.
7. Select *Say a simple phrase*.
8. Choose up to three commands you want to say to trigger the action, e.g. :
 - *Open the garage*
 - *Close the garage*
 - *Open the garage, please*
9. Choose a response from the Assistant.
10. Create the trigger.
11. Select the action service *Webhooks*.
12. Select *Make a web request*.
13. Fill in the URL, method (POST), content type (application/json) and body as described in the **Actions** page in step 4.
14. Create the action.
15. Don't be to impatient, but you should now be able to test out the command you just created, e.g. *OK Google, open the garage*.

**Unlike the dimmer actions you need to add both a new action and a new "applet" if you want to create another one.**

## Security
I strongly recommend using HTTPS for the server. Setting that up is a bit out of scope for this document, but you should
really look into it. If not using HTTPS someone on the Internet might pick up the commands you are sending. The commands
can replayed and someone might guess how to use other commands.

The reason there is no login for the frontend of the web server is simple: The moment someone has access to the network
where there are HDL Buspro traffic they don't need this server. All communication is done in plain text with no
security. Therefore securing the frontend with a login would add a false sense of security. **The web server front end
(not the API for the commands) requires the user to be in the same local network.**

The security for the API and the commands lies in an automatically created random key when starting the server the first
time. If the requests for the commands are missing the correct key they will not be executed.

**Please [update](#updating-the-server) if you run version 1.0.3 or earlier.** They have known security vulnerabilities.

Keep in mind that there could potentially be security holes or weaknesses in the code of this project, the web framework this is built
on, the built-in application server this runs on, the server you are running, your router, other network components,
IFTTT and Google Assistant. You have no guarantees. If you want to stay safe, you should stay offline.

If you want to learn a little more about security and HDL Buspro you can read my blog post at
https://blog.roysolberg.com/2017/08/my-dumb-smart-home .

## Building from source
You need Java 17 or later:  
`./gradlew build`

This runs the tests and builds the runnable JAR at `build/libs/hdlserver-<version>.jar`, which is what's released as
`releases/hdlserver-LATEST.jar`.

## Links
 - This repo: https://github.com/roys/java-hdl-webserver
 - My Android app for controlling HDL Buspro: https://play.google.com/store/apps/details?id=com.roysolberg.android.smarthome
 - YouTube demo: https://youtu.be/P6BgsM8IiW8
 - HDL Buspro: http://www.hdlautomation.com
 - Google Assistant: https://assistant.google.com
 - Google Home: https://madeby.google.com/home/
 - Google Assistant at IFTTT: https://ifttt.com/google_assistant
 - Webhooks at IFTTT: https://ifttt.com/maker_webhooks
