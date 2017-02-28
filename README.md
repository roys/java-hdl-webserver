# Web server for HDL Buspro
**This is a web server for setting up and communicating with the smart home system *HDL Buspro*. It was mainly built for
the purpose of integrating with *Google Assistant* (Google Home and Google Pixel).**

## Table of Contents
- [Video demo](#video-demo)
- [How it works](#how-it-works)
- [Example commands](#example-commands)
- [Running the server - quick and dirty](#running-the-server---quick-and-dirty)
- [Running the server - complete setup on a Raspberry Pi](#running-the-server---complete-setup-on-a-raspberry-pi-running-raspbian-gnulinux)
- [Security](#security)
- [Links](#links)

## Video demo
[![YouTube proof of concept video](http://img.youtube.com/vi/P6BgsM8IiW8/0.jpg)](https://youtu.be/P6BgsM8IiW8)

## How it works
Google have added some [triggers at IFTTT](https://ifttt.com/google_assistant) for Google Assistant (i.e. Google Home
and Google Pixel). Combining them with the [Maker actions](https://ifttt.com/maker), a web request can be sent to the
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
 - [Java 8](https://java.com/en/download/)
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

You should now be ready to [create some actions](#part-2-adding-commands-actions).

## Running the server - complete setup on a Raspberry Pi running Raspbian GNU/Linux

### Part 1: Download and set up the server

#### 1. Java setup

First SSH into your Raspberry Pi.

Type `java -version` to see which Java version you have. If it says something with `1.8` or later you are good to go. Just continue to the next step.

If your Java version was less than 1.8, run the following commands and follow the setup:  
`sudo apt-get update`  
`sudo apt-get upgrade`  
`sudo apt-get install oracle-java8-jdk`  

#### 2. Directory setup and getting the server

First check out the code:  
`cd ~`  
`git clone https://github.com/roys/java-hdl-webserver.git`  

It can be smart to check that the server is working and getting data:  
`cd java-hdl-webserver/releases`  
`java -cp hdlserver-LATEST.jar com.roysolberg.java.hdlserver.Application`

You should see some output confirming that the server is starting up ok. Please note that it could take a few seconds to start up on hardware like one of the Raspberry Pi models.

Use `ctrl + c` to exit the application.

#### 3a. Running the server in the background

If you want to make the server run on startup then skip to **3b**. If you want to just run the server in the background (even afer you log out) you can enter the following
command:  
`nohup java -cp hdlserver-LATEST.jar com.roysolberg.java.hdlserver.Application &`  

The number that is printed out is the process number. You can use this number to stop the script.

If you don't have the process number you can find it using `ps aux | grep hdlserver`.

To kill/stop the script just enter the following command:
`kill [process number]`  

#### 3b. Running the server on startup

The server can be run as a cron job that starts on reboot of the Raspberry Pi.

To edit the cronjob:  
`crontab -e`

Add the following line at the end:  
`@reboot /bin/sh ~/java-hdl-webserver/launcher.sh`

Use `ctrl + x` to exit, press `Y` to save the cron setup and `enter` to confirm the filename.

Restart the device using `sudo shutdown -r now` and check out if there's stuff going on in `releases/logs/hdl-bus.log`. `releases/logs/hdl-server.log` and `releases/logs/nohup.out` will also contain output and any error messages running the server. Please note that there is a delay of 30 seconds when running the server from the `launcher.sh` script (to ensure that network interfaces are up and IP address is assigned, etc).

#### 4. Reading the logs

You can easily tap in to what's going by running `tail`:  
`tail -f hdl-bus.log`  
`tail -f hdl-server.log`  

This will let you follow the file and the contents that's written to it. To end the session just press `ctrl + c`.

#### 5. Remote access

To be able to have IFTTT access (described later) the server and run the commands you set up you have to ensure that the server is accessible from the Internet. How to do this depends on your Internet router, but in general it will be like this:
 - Give the computer running the server a static IP address in your router
 - Forward a port (please use some other high number port than 4567) from Internet to port 4567 to the computer running the server
 - Set up some kind of dynamic DNS (can often be done on the router itself, or you can use the computer running the server) to get a static hostname for IFTTT
 - Use the hostname and external port number when setting up IFTTT (described later)

#### Updating the server

If you chose to do a `git clone` in the first step you can just run `git pull` inside the directory at `~/java-hdl-webserver`. If you run the script at boot
time it is easiest to just reboot your Raspberry Pi using `sudo shutdown -r now` to ensure the latest version of the script is running.

### Part 2: Adding commands (actions)

Prerequisites: An account at https://ifttt.com and a Google account.

#### Create dimmer actions

Dimmer actions let you say commands like *Dim the lights to 20% in the kitchen* and are meant for HDL dimmers.

1. Go to to the **Actions** page.
2. Below **Create dimmer action**, type in the name of the area. This will be the area you asked to be dimmed, e.g. *The kitchen*.  
You can also add zero to many aliases for the area if you use different names for it. If using *"the"* in an area name you might want to repeat the name of the area without *"the"*. Then everything will work if you say *Dim the lights to 20% in kitchen*.
3. Select the dimmer channel representing the area.  
If you want the same area to control several areas or you have several channels for the same area you can add those as well.
4. Select *Create action* and notice the info about the action at the bottom of the page.  
  
5. Go to https://ifttt.com/create to create a new "applet" (preferably in a new browser window/tab).
6. Select the service *Google Assistant*.
7. Select *Say a phrase with both a number and a text ingredient*.
8. Choose up to three commands you want to say to trigger the action, e.g. :
 - *Dim the lights to # % in $*
 - *Turn up the lights to # % in $*
 - *Set the lights to # % in $*
9. Choose a response from the Assistant. It can be nice to include the input from your command, e.g. *OK, # % in $*.
10. Create the trigger.
11. Select the action service *Maker*.
12. Select *Make a web request*.
13. Fill in the URL, method (POST), content type (application/json) and body as described in the **Actions** page in step 4.
14. Create the action.

15. Don't be to impatient, but you should now be able to test out the command you just created, e.g. *OK Google, dim the lights to 20% in the kitchen*.

#### Create custom actions

Custom actions let you give custom commands like *Open the garage* which can trigger some panel button or set a universal switch.

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
11. Select the action service *Maker*.
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

Keep in mind that there could potentially be security holes or weaknesses in the code of this project, the web framework this is built
on, the built-in application server this runs on, the server you are running, your router, other network components,
IFTTT and Google Assistant. You have no guarantees. If you want to stay safe, you should stay offline.

If you want to learn a little more about security and HDL Buspro you read my Google+ post at
https://plus.google.com/+RoySolberg/posts/gNt4paU7KEZ .

## Links
 - This repo: https://github.com/roys/java-hdl-webserver
 - My Android app for controlling HDL Buspro: https://play.google.com/store/apps/details?id=com.roysolberg.android.smarthome
 - YouTube demo: https://youtu.be/P6BgsM8IiW8
 - HDL Buspro: http://www.hdlautomation.com
 - Google Assistant: https://assistant.google.com
 - Google Home: https://madeby.google.com/home/
 - Google Assistant at IFTTT: https://ifttt.com/google_assistant
 - Maker at IFTTT: https://ifttt.com/maker
