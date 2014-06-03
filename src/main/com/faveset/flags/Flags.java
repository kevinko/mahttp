// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.flags;

import java.io.PrintStream;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A "-help" flag will always be installed.  It will print help for the current flag configuration
 * and then perform a System.exit.
 *
 * Example usage:
 *   public class Foo {
 *     private BoolFlag mFooFlag = Flags.registerBool("foo", true, "enable foo");
 *
 *     public static void main(String[] args) {
 *       Flags.parse();
 *       if (mFooFlag.get()) {
 *         // ...
 *       }
 *     }
 *   }
 */
public class Flags {
    // The singleton flags object.
    private static Flags sFlags;

    private static String sHelpFlagName = "help";

    private ArrayList<String> mNonFlagArgs;

    // Keyed by flag name.
    private Map<String, FlagParser> mFlagParsers;

    private Flags() {
        mNonFlagArgs = new ArrayList<String>();
        mFlagParsers = new HashMap<String, FlagParser>();
    }

    /**
     * Returns the singleton Flags object.
     */
    public static Flags get() {
        if (sFlags == null) {
            sFlags = new Flags();
        }
        return sFlags;
    }

    /**
     * @return the non-flag argument of given index.
     */
    public String getArg(int index) {
        return mNonFlagArgs.get(index);
    }

    /**
     * @return the number of non-flag arguments.
     */
    public int getArgSize() {
        return mNonFlagArgs.size();
    }

    /**
     * @return an array holding all non-flag arguments.
     */
    public String[] getArgs() {
        return mNonFlagArgs.toArray(new String[0]);
    }

    /**
     * @return the leading flag prefix ("-" or "--") or the empty String
     * if none is found.
     */
    private static String getFlagPrefix(String s) {
        if (s.startsWith("--")) {
            return "--";
        }
        if (s.startsWith("-")) {
            return "-";
        }
        return "";
    }

    /**
     * Call this method to parse the flags in args.
     *
     * @throws IllegalArgumentException if an unknown flag is encountered.
     */
    public static void parse(String[] args) throws IllegalArgumentException {
        get().parseImpl(args);
    }

    /**
     * Call this method to parse the flags in args.
     *
     * @throws IllegalArgumentException if an unknown flag is encountered.
     */
    private void parseImpl(String[] args) throws IllegalArgumentException {
        final int len = args.length;
        for (int ii = 0; ii < len; ii++) {
            String arg = args[ii];
            String flagPrefix = getFlagPrefix(arg);
            if (flagPrefix.isEmpty()) {
                mNonFlagArgs.add(arg);
                continue;
            }

            // Determine the flag name.
            String flagName;
            String flagValue;

            int equalsIndex = arg.indexOf("=");
            if (equalsIndex == -1) {
                flagName = arg.substring(flagPrefix.length());
                flagValue = new String();
            } else {
                flagName = arg.substring(flagPrefix.length(), equalsIndex);
                flagValue = arg.substring(equalsIndex + 1);
            }

            FlagParser parser = mFlagParsers.get(flagName);
            if (parser == null) {
                // See if this is a help flag.
                if (flagName.equals(sHelpFlagName)) {
                    writeHelp(System.out);

                    System.exit(0);
                } else {
                    throw new IllegalArgumentException(String.format("unknown flag %s", flagName));
                }
            }

            // Now, finalize the value if it is not embedded within the
            // flag argument.  Non-singular flags can use the following
            // argument as the value.
            if (equalsIndex == -1 && !parser.isSingular()) {
                // Check the next argument for the value.
                int nextInd = ii + 1;
                if (nextInd < len) {
                    String nextArg = args[nextInd];
                    if (getFlagPrefix(nextArg).isEmpty()) {
                        // We found the value.
                        flagValue = nextArg;
                    }
                }
            }

            parser.parse(flagValue);
        }
    }

    private void registerFlag(String name, FlagParser flag) {
        mFlagParsers.put(name, flag);
    }

    public static BoolFlag registerBool(String name, boolean defValue, String desc) {
        BoolFlag flag = new BoolFlag(defValue, desc);
        get().registerFlag(name, flag);
        return flag;
    }

    public static StringFlag registerString(String name, String defValue, String desc) {
        StringFlag flag = new StringFlag(defValue, desc);
        get().registerFlag(name, flag);
        return flag;
    }

    public void writeHelp(PrintStream out) {
        StringBuilder builder = new StringBuilder();
        writeHelp(builder);
        out.println(builder.toString());
    }

    /**
     * Writes a help message for the configured flags using builder.
     */
    public void writeHelp(StringBuilder builder) {
        Set<String> keys = mFlagParsers.keySet();
        List<String> keyList = new ArrayList<String>(keys);
        java.util.Collections.sort(keyList);

        boolean isFirst = true;
        for (String key : keyList) {
            if (isFirst) {
                isFirst = false;
            } else {
                builder.append('\n');
            }

            FlagParser p = mFlagParsers.get(key);
            builder.append(String.format("  -%s=%s: %s",
                        key, p.getDefaultValueString(), p.getDesc()));
        }
    }
}
