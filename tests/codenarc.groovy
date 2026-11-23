// CodeNarc rules for the Hubitat apps and drivers. These are Groovy scripts that run in Hubitat's sandbox, so the rules
// are chosen for what catches real mistakes there (dead code, unused values, bad imports, risky APIs) and not for style:
// formatting, braces, "no def", duplicate literals and GString rules would only produce noise here.
//
// Every rule below currently passes, so a new violation is a regression. To see what another rule family would say
// about the code, run:  groovy tests/lint.groovy path/to/a/ruleset.groovy
// (for example a file containing:  ruleset { ruleset('rulesets/size.xml') } )
ruleset {
    description 'Hubitat UNO integration'

    ruleset('rulesets/basic.xml') {
        // The partition driver implements Hubitat's Alarm capability, whose siren, strobe and both commands the UNO
        // doesn't have, so they exist and do nothing.
        'EmptyMethod' {
            doNotApplyToFilesMatching = '.*UNOPartitionDriver.groovy'
        }
    }
    ruleset('rulesets/unused.xml') {
        // The partition driver implements Hubitat's SecurityKeypad and HSM entry points (armAway(delay), setCode(...)),
        // whose signatures are fixed by the capability even where the UNO has no use for an argument.
        'UnusedMethodParameter' {
            doNotApplyToFilesMatching = '.*UNOPartitionDriver.groovy'
        }
    }
    ruleset('rulesets/concurrency.xml')
    ruleset('rulesets/generic.xml')
    ruleset('rulesets/imports.xml')
    ruleset('rulesets/logging.xml')
    ruleset('rulesets/naming.xml')
    ruleset('rulesets/security.xml')
    ruleset('rulesets/serialization.xml')
    // Methods must stay under the default cyclomatic complexity of 20. The rest of the size family (method and class
    // length, method count, ABC score) is left out: a Hubitat app or driver is one script, so its method count and the
    // length of a page-building method are set by the platform and not by how tangled the logic is.
    ruleset('rulesets/size.xml') {
        include 'CyclomaticComplexity'
    }
    ruleset('rulesets/exceptions.xml') {
        // The scripts catch broad exceptions on purpose: one bad frame or one failed device call must not stop the
        // rest, and helpers such as the number and firmware parsers return null to say "couldn't read that".
        exclude 'CatchException'
        exclude 'CatchThrowable'
        exclude 'CatchError'
        exclude 'ReturnNullFromCatchBlock'
    }
}
