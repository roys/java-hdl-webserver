# Web server for HDL Buspro
**This is a web server for setting up and communicating with the smart home system *HDL Buspro*. It was mainly built for
the purpose of integrating with *Google Assistant* (Google Home and Google Pixel).**

## Table of Contents
- [Video demo](#video-demo)
- [How it works](#how-it-works)
- [Example commands](#example-commands)
- [Running the server](#running-the-server)
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

## Running the server

### Part 1: Download and set up the server

Give me a few days to finish up the instructions. :)

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
