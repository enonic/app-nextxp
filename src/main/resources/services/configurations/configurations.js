const nextjsConfigLib = require('/lib/nextxp/config');

exports.get = function (request) {
    const configs = nextjsConfigLib.listConfigurations();
    return {
        status: 200,
        body: JSON.stringify({
            hits: configs.map(config => {
                const name = config.name;
                return {
                    id: name,
                    displayName: name.charAt(0).toUpperCase() + name.substring(1),
                    description: config.url
                };
            }),
            count: configs.length,
            total: configs.length,
        }),
        contentType: 'application/json'
    };
}