const nextjsEventLib = require('/lib/nextxp/event');

// Subscribe on every app node so a new leader already has a listener.
nextjsEventLib.subscribe();
