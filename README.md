# Web server for HDL Buspro
**This is a web server for setting up and communicating with the smart home system *HDL Buspro*. It was mainly built for
the purpose of integrating with *Google Assistant* (Google Home and Google Pixel).**

## Demo video
[![YouTube proof of concept video](http://img.youtube.com/vi/P6BgsM8IiW8/0.jpg)](https://youtu.be/P6BgsM8IiW8)

## Running the server
Give me a few days to write up the instructions. :)

## How it works
Google have added some [triggers at IFTTT](https://ifttt.com/google_assistant) for Google Assistant (i.e. Google Home
and Google Pixel). Combining them with the [Maker actions](https://ifttt.com/maker) a web request can be sent to the
HDL Buspro server in this project. The server interprets the requests by using the actions you have set up and sends the
belonging HDL commands to the IP gateway.

## Example of commands
 - *Dim the lights to [X]% in [area/room]*
 - *Set the lights to [X]% in [area/room]*
 - *Turn [on/off] the lights in [area/room]*
 - *Turn [on/off] the TV*
 - *[Open/close] the garage*
 - *Bye bye*
 - *Turn [on/off] the alarm*
 - *Send for help*
 - *Alarm [on/off]*

## Setting up the commands
Give me a few days to write up the instructions. :)

## Security
I strongly recommend using HTTPS for the server. Setting that up is a bit out of scope for this document, but you should
really look into it. If not using HTTPS someone on the Internet might pick up the commands you are sending. The commands
can replayed and someone might guess other how to use other commands.

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
https://plus.google.com/u/0/+RoySolberg/posts/gNt4paU7KEZ .

## Links
 - This repo: https://github.com/roys/java-hdl-webserver
 - My Android app for controlling HDL Buspro: https://play.google.com/store/apps/details?id=com.roysolberg.android.smarthome
 - YouTube demo: https://youtu.be/P6BgsM8IiW8
 - HDL Buspro: http://www.hdlautomation.com
 - Google Assistant: https://assistant.google.com
 - Google Home: https://madeby.google.com/home/
 - Google Assistant at IFTTT: https://ifttt.com/google_assistant
 - Maker at IFTTT: https://ifttt.com/maker
