const clusterLib = require('/lib/xp/cluster');
const nextjsEventLib = require('/lib/nextjs/event');


const initialize = function () {
    nextjsEventLib.subscribe();
};


if (clusterLib.isMaster()) {
    initialize();
}
