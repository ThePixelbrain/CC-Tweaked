// SPDX-FileCopyrightText: 2023 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.core;

import dan200.computercraft.core.apis.http.options.Action;
import dan200.computercraft.core.apis.http.options.AddressRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

/**
 * Config options for CC: Tweaked's Lua runtime.
 *
 * @see dan200.computercraft.shared.config.Config
 */
public final class CoreConfig {
    private CoreConfig() {
    }

    /**
     * The default HTTP rules. These deny access to private address ranges, and allow access to everything else.
     */
    public static final List<AddressRule> DEFAULT_HTTP_RULES = Collections.unmodifiableList(Arrays.asList(
        AddressRule.parse("$private", OptionalInt.empty(), Action.DENY.toPartial()),
        AddressRule.parse("*", OptionalInt.empty(), Action.ALLOW.toPartial())
    ));

    public static volatile List<AddressRule> httpRules = DEFAULT_HTTP_RULES;
}
