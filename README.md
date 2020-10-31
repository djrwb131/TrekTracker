# TrekTracker - the personal tracking app

TrekTracker is a personal tracking app for tracking things like hikes, trail rides, and geocaching. It stores your Treks locally, with the
option of cloud backups and sharing.

# How it works
TrekTracker sets up a foreground service which periodically checks your location using GPS. It does not use any Google Play services or API,
so it can be used in machines without Google Play. This information is stored in a local database until the Trek is done, or a sync is requested
by the user.

## Encrypted data
Once a Trek is complete, there is an option to encrypt with a password *provided by the user, and which is definitely not ASDF*. The Trek is then
signed by an RSA key in the device. This RSA key, of course, is generated specifically for this purpose in order to ensure ownership. This allows
the server to determine who owns the Trek data.

## Decrypt and share
If you don't mind the server having access to your Trek, a password may be omitted during sync. This allows you to share Trek information with
friends. The data will be encrypted with a server-known secret key, and only shared with people that you allow. 

## Detailed stats
Treks can be analysed on your device to see speeds for every section of your favourite trail. These sections can even be marked! Watch your speeds
improve for each section of your favorite trail (yes, even the climb).

## TODOs
 - Cloud sync
 - UI improvements 
 - Options menu
 - Section marking
 - Non-Google map 
 - More reasonable code organisation (adhere to MVVM properly)
 - Unit testing
