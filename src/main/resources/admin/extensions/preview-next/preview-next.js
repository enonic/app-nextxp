/*global app, resolve, require*/

const widgetLib = require('/lib/export/widget');
const configLib = require('/lib/export/config');
const mappingsLib = require('/lib/export/mappings');
const mappingsResolver = __.newBean('com.enonic.app.preview.nextjs.UrlMappingsResolver');
const payloadEncoder = __.newBean('com.enonic.app.preview.nextjs.PayloadEncoder');

const SHORTCUT_TYPE = 'base:shortcut';

exports.get = function (req) {
    let params;
    try {
        params = widgetLib.validateParams(req.params);
    } catch (e) {
        return widgetLib.widgetResponse(400);
    }

    const site = widgetLib.fetchSite(params.repository, params.branch, params.id, params.archive);
    const serverConfig = configLib.getServerConfig(site);
    const secret = serverConfig.secret;

    let mappings;
    let encryptedPayload;
    if (secret) {
        encryptedPayload = payloadEncoder.encode(JSON.stringify({
            xpProject: widgetLib.getProjectName(params.repository),
        }), secret);
    }

    try {
        mappings = mappingsLib.getMappings(serverConfig.url, encryptedPayload);
    } catch (e) {
        log.error('Next preview: failed to fetch mappings: ' + e.message);
        return widgetLib.widgetResponse(500);
    }

    const resolverMappings = mappingsLib.toResolverConfig(serverConfig, mappings);

    const configs = {};
    configs['default'] = { mappings: resolverMappings };

    const result = mappingsResolver.resolve(params, __.toScriptValue(configs));
    const mappingUrl = result ? result.url : null;


    if (!exports.canRender(params, mappingUrl)) {
        log.debug('Next preview [' + req.method + '] can\'t render: 418');
        return widgetLib.widgetResponse(418);
    }

    try {
        const nextUrl = widgetLib.buildNextUrl(mappingUrl, encryptedPayload);

        const data = {
            redirect: nextUrl,
        };

        log.debug('Next preview [' + req.method + '] response: ' + JSON.stringify(data));

        if (params.mode === 'inline' || params.mode === 'edit') {
            return widgetLib.widgetResponse(200, data);
        }

        return widgetLib.redirectResponse(nextUrl, data);

    } catch (e) {
        log.error('Next preview [' + req.method + '] error: ' + e.message);
        return widgetLib.widgetResponse(500);
    }
};

exports.canRender = function (params, mapping) {
    if (!mapping) {
        log.info('Next preview [CAN_RENDER] no mapping found');
        return false;
    }

    return params.type !== SHORTCUT_TYPE && !params.archive;
};
