// SPDX-FileCopyrightText: 2020 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.config;

import dan200.computercraft.core.apis.http.options.Action;
import dan200.computercraft.core.apis.http.options.AddressRule;
import dan200.computercraft.core.apis.http.options.InvalidRuleException;
import dan200.computercraft.core.apis.http.options.PartialOptions;

import java.util.*;
import java.util.stream.Collectors;

import static cc.tweaked.CCTweaked.LOG;

/**
 * Parses and generates {@link AddressRule}s from config strings.
 * <p>
 * Forge's config system does not support lists of tables, so each rule is written as a single string, containing a
 * series of space-separated {@code key=value} pairs. For example, {@code host=$private action=deny}.
 * <p>
 * Forge's config parser treats characters such as {@code =}, {@code :} and {@code *} specially even inside lists, so
 * rules must be wrapped in double quotes in the config file. These quotes are preserved by Forge, and stripped by
 * {@link #parseRules(String[])}.
 */
final class AddressRuleConfig {
    private static final AddressRule REJECT_ALL = AddressRule.parse("*", OptionalInt.empty(), Action.DENY.toPartial());

    private static final Set<String> knownKeys = new HashSet<>(Arrays.asList(
        "host", "port", "action", "max_download", "max_upload", "max_websocket_message"
    ));

    private AddressRuleConfig() {
    }

    static String[] defaultRules() {
        return new String[]{
            "\"host=$private action=deny\"",
            "\"host=* action=allow"
                + " max_download=" + AddressRule.MAX_DOWNLOAD
                + " max_upload=" + AddressRule.MAX_UPLOAD
                + " max_websocket_message=" + AddressRule.WEBSOCKET_MESSAGE + "\"",
        };
    }

    static List<AddressRule> parseRules(String[] rules) {
        var out = new ArrayList<AddressRule>(rules.length);
        for (var rule : rules) {
            rule = unquote(rule.trim());
            if (rule.isEmpty()) continue;
            out.add(parseRule(rule));
        }
        return Collections.unmodifiableList(out);
    }

    private static String unquote(String rule) {
        return rule.length() >= 2 && rule.startsWith("\"") && rule.endsWith("\"")
            ? rule.substring(1, rule.length() - 1).trim()
            : rule;
    }

    static AddressRule parseRule(String rule) {
        try {
            return doParseRule(rule);
        } catch (InvalidRuleException e) {
            LOG.severe("Malformed HTTP rule '" + rule + "': " + e.getMessage() + " HTTP will NOT work until this is fixed.");
            return REJECT_ALL;
        }
    }

    static AddressRule doParseRule(String rule) {
        var fields = new LinkedHashMap<String, String>();
        for (var entry : rule.trim().split("\\s+")) {
            var idx = entry.indexOf('=');
            if (idx <= 0) throw new InvalidRuleException(String.format("Expected 'key=value', but got '%s'.", entry));

            var key = entry.substring(0, idx).toLowerCase(Locale.ROOT);
            if (fields.put(key, entry.substring(idx + 1)) != null) {
                throw new InvalidRuleException(String.format("Field '%s' is specified multiple times.", key));
            }
        }

        var hostObj = fields.get("host");
        if (hostObj == null || hostObj.isEmpty()) throw new InvalidRuleException("No 'host' specified");

        var action = getEnum(fields, "action", Action.class).orElse(null);
        var port = getInt(fields, "port");
        var maxUpload = getLong(fields, "max_upload");
        var maxDownload = getLong(fields, "max_download");
        var websocketMessage = getInt(fields, "max_websocket_message");

        // Find unknown keys and warn about them.
        var unknownKeys = fields.keySet().stream().filter(x -> !knownKeys.contains(x)).collect(Collectors.toList());
        if (!unknownKeys.isEmpty()) {
            LOG.warning(String.format(
                "Unknown config %s %s in address rule.", unknownKeys.size() == 1 ? "option" : "options", String.join(", ", unknownKeys)
            ));
        }

        var options = new PartialOptions(
            action,
            maxUpload,
            maxDownload,
            websocketMessage,
            Optional.empty()
        );

        return AddressRule.parse(hostObj, port, options);
    }

    private static OptionalInt getInt(Map<String, String> fields, String field) {
        var value = fields.get(field);
        if (value == null) return OptionalInt.empty();

        try {
            return OptionalInt.of(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new InvalidRuleException(String.format("Field '%s' should be a number, but is '%s'.", field, value));
        }
    }

    private static OptionalLong getLong(Map<String, String> fields, String field) {
        var value = fields.get(field);
        if (value == null) return OptionalLong.empty();

        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new InvalidRuleException(String.format("Field '%s' should be a number, but is '%s'.", field, value));
        }
    }

    private static <T extends Enum<T>> Optional<T> getEnum(Map<String, String> fields, String field, Class<T> klass) {
        var value = fields.get(field);
        return value == null ? Optional.empty() : Optional.of(parseEnum(field, klass, value));
    }

    private static <T extends Enum<T>> T parseEnum(String field, Class<T> klass, String x) {
        var values = Objects.requireNonNull(klass.getEnumConstants());
        for (var value : values) {
            if (value.name().equalsIgnoreCase(x)) return value;
        }

        throw new InvalidRuleException(String.format(
            "Field '%s' should be one of %s, but is '%s'.",
            field, Arrays.stream(values).map(Enum::name).collect(Collectors.toList()), x
        ));
    }
}
