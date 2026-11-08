// SPDX-FileCopyrightText: 2023 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.config;

import dan200.computercraft.core.CoreConfig;
import net.minecraftforge.common.Configuration;

import java.io.File;
import java.util.logging.Level;

import static cc.tweaked.CCTweaked.LOG;

/**
 * Loads CC: Tweaked's config file. This is separate from ComputerCraft's own config, which still controls whether the
 * HTTP API is enabled at all.
 */
public final class Config {
    private static final String CATEGORY_HTTP = "http";
    private static final String HTTP_RULES = "rules";

    private Config() {
    }

    public static void load(File file) {
        Configuration config;
        try {
            // Note: The constructor also loads the file.
            config = new Configuration(file);
        } catch (RuntimeException e) {
            // Forge throws on malformed config files. Rather than crashing (or overwriting the user's config with the
            // defaults), fall back to the default rules.
            LOG.log(Level.SEVERE, "Failed to load " + file + ". Using the default HTTP rules until this is fixed.", e);
            CoreConfig.httpRules = CoreConfig.DEFAULT_HTTP_RULES;
            return;
        }

        config.addCustomCategoryComment(CATEGORY_HTTP, """
            Controls the "http" API. The API itself must also be enabled with the "enableAPI_http"
            option in ComputerCraft's config.""");

        var rules = config.get(CATEGORY_HTTP, HTTP_RULES, AddressRuleConfig.defaultRules(), """
            A list of rules which control behaviour of the "http" API for specific domains or
            IPs. Each rule matches against a hostname and an optional port, and then sets several
            properties for the request. Rules are evaluated in order, meaning earlier rules override
            later ones.

            Each rule is written on its own line, as a list of space-separated key=value pairs
            wrapped in double quotes, such as "host=$private action=deny". The quotes are required,
            as Forge cannot otherwise read rules containing characters like "=", "*" or ":".

            Valid properties:
             - "host" (required): The domain or IP address this rule matches. This may be a domain name
               ("pastebin.com"), wildcard ("*.pastebin.com") or CIDR notation ("127.0.0.0/8").
               The magic "$private" host matches all private address ranges, such as localhost and
               192.168.0.0/16. Denying these prevents computers accessing internal services, and is
               strongly recommended.
             - "port" (optional): Only match requests for a specific port, such as 80 or 443.

             - "action" (optional): Whether to allow or deny this request.
             - "max_download" (optional): The maximum size (in bytes) that a computer can download in this
               request.
             - "max_upload" (optional): The maximum size (in bytes) that a computer can upload in this request.
             - "max_websocket_message" (optional): The maximum size (in bytes) that a computer can send or
               receive in one websocket packet.""");
        CoreConfig.httpRules = AddressRuleConfig.parseRules(rules.valueList);

        // An unquoted rule containing "=" causes Forge to misread the list under a different name. Warn about this, as
        // otherwise the user's rules are silently ignored.
        for (var key : config.getCategory(CATEGORY_HTTP).keySet()) {
            if (key.equals(HTTP_RULES)) continue;
            LOG.warning("Unknown option '" + key + "' in " + file + ". Check that each HTTP rule is wrapped in double quotes.");
        }

        config.save();
    }
}
