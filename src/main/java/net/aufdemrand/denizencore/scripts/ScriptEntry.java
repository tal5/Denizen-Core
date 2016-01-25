package net.aufdemrand.denizencore.scripts;

import net.aufdemrand.denizencore.DenizenCore;
import net.aufdemrand.denizencore.exceptions.InvalidArgumentsException;
import net.aufdemrand.denizencore.exceptions.ScriptEntryCreationException;
import net.aufdemrand.denizencore.objects.Element;
import net.aufdemrand.denizencore.objects.dObject;
import net.aufdemrand.denizencore.objects.dScript;
import net.aufdemrand.denizencore.scripts.commands.AbstractCommand;
import net.aufdemrand.denizencore.scripts.commands.BracedCommand;
import net.aufdemrand.denizencore.scripts.commands.Holdable;
import net.aufdemrand.denizencore.scripts.containers.ScriptContainer;
import net.aufdemrand.denizencore.scripts.queues.ScriptQueue;
import net.aufdemrand.denizencore.utilities.CoreUtilities;
import net.aufdemrand.denizencore.utilities.debugging.Debuggable;
import net.aufdemrand.denizencore.utilities.debugging.dB;

import java.util.*;


/**
 * ScriptEntry contain information about a single entry from a dScript. It is used
 * by the CommandExecuter, among other parts of Denizen.
 */
public class ScriptEntry implements Cloneable, Debuggable {

    // The name of the command that will be executed
    private String command;
    private AbstractCommand actualCommand;

    // Command Arguments
    private List<String> args;
    private List<String> pre_tagged_args;
    private List<String> modified_arguments;

    // TimedQueue features
    private boolean instant = false;
    private boolean waitfor = false;

    // 'Attached' core context
    public ScriptEntryData entryData;
    private dScript script = null;
    private ScriptQueue queue = null;

    private List<BracedCommand.BracedData> bracedSet = null;

    public List<BracedCommand.BracedData> getBracedSet() {
        return bracedSet;
    }

    public void setBracedSet(List<BracedCommand.BracedData> set) {
        bracedSet = set;
    }

    // ScriptEntry Context
    private Map<String, Object> objects = new HashMap<String, Object>();


    // Allow cloning of the scriptEntry. Can be useful in 'loops', etc.
    @Override
    public ScriptEntry clone() throws CloneNotSupportedException {
        ScriptEntry se = (ScriptEntry) super.clone();
        se.objects = new HashMap<String, Object>();
        se.modified_arguments = new ArrayList<String>(modified_arguments);
        se.entryData = entryData.clone();
        return se;
    }

    private List<Object> insideList;

    public List<Object> getInsideList() {
        return insideList;
    }


    /**
     * Get a hot, fresh, script entry, ready for execution! Just supply a valid command,
     * some arguments, and bonus points for a script container (can be null)!
     *
     * @param command   the name of the command this entry will be handed to
     * @param arguments an array of the arguments
     * @param script    optional ScriptContainer reference
     * @throws ScriptEntryCreationException if 'command' is null
     */
    public ScriptEntry(String command, String[] arguments, ScriptContainer script) throws ScriptEntryCreationException {
        this(command, arguments, script, null);
    }

    public ScriptEntry(String command, String[] arguments, ScriptContainer script, List<Object> insides) throws ScriptEntryCreationException {

        if (command == null) {
            throw new ScriptEntryCreationException("dCommand 'name' cannot be null!");
        }

        entryData = DenizenCore.getImplementation().getEmptyScriptEntryData();

        this.command = command.toUpperCase();

        insideList = insides;

        // Knowing which script created this entry provides important context. We'll store
        // a dScript object of the container if script is not null.
        // Side note: When is script ever null? Think about situations such as the /ex command,
        // in which a single script entry is created and sent to be executed.
        if (script != null) {
            this.script = script.getAsScriptArg();
        }

        // Check if this is an 'instant' or 'waitfor' command. These are
        // features that are used with 'TimedScriptQueues'
        if (command.length() > 0) {
            if (command.charAt(0) == '^') {
                instant = true;
                this.command = command.substring(1).toUpperCase();
            }
            else if (command.charAt(0) == '~') {
                this.command = command.substring(1).toUpperCase();
                // Make sure this command can be 'waited for'
                if (DenizenCore.getCommandRegistry().get(this.command)
                        instanceof Holdable) {
                    waitfor = true;
                }
                else {
                    dB.echoError("The command '" + this.command + "' cannot be waited for!");
                }
            }
            actualCommand = DenizenCore.getCommandRegistry().get(this.command);
        }
        else {
            actualCommand = null;
        }

        // Store the args. The CommandExecuter will fill these out.
        if (arguments != null) {
            this.args = Arrays.asList(arguments);
            // Keep seperate list for 'un-tagged' copy of the arguments.
            // This will be useful if cloning the script entry for use in a loop, etc.
            this.pre_tagged_args = Arrays.asList(arguments);
            this.modified_arguments = Arrays.asList(arguments);
        }
        else {
            this.args = new ArrayList<String>();
            this.pre_tagged_args = new ArrayList<String>();
            this.modified_arguments = new ArrayList<String>();
        }

        // Check for replaceable tags. We'll try not to make a habit of checking for tags/doing
        // tag stuff if the script entry doesn't have any to begin with.
        argLoop:
        for (String arg : args) {
            boolean left = false, right = false;
            for (int i = 0; i < arg.length(); i++) {
                char c = arg.charAt(i);
                if (c == '<') {
                    left = true;
                }
                if (c == '>') {
                    right = true;
                }
                if (left && right) {
                    has_tags = true;
                    break argLoop;
                }
            }
        }

        if (actualCommand != null && actualCommand instanceof BracedCommand) {
            BracedCommand.getBracedCommands(this);
        }
    }


    // As explained, this is really just a micro-performance enhancer
    public boolean has_tags = false;


    /**
     * Adds a context object to the script entry. Just provide a key and an object.
     * Technically any type of object can be stored, however providing dObjects
     * is preferred.
     *
     * @param key    the name of the object
     * @param object the object, preferably a dObject
     */
    public ScriptEntry addObject(String key, Object object) {
        if (object == null) {
            return this;
        }
        if (object instanceof dObject) {
            ((dObject) object).setPrefix(key);
        }
        objects.put(CoreUtilities.toLowerCase(key), object);
        return this;
    }


    /**
     * If the scriptEntry lacks the object corresponding to the
     * key, set it to the first non-null argument
     *
     * @param key The key of the object to check
     * @return The scriptEntry
     */
    public ScriptEntry defaultObject(String key, Object... objects) throws InvalidArgumentsException {
        if (!this.objects.containsKey(CoreUtilities.toLowerCase(key))) {
            for (Object obj : objects) {
                if (obj != null) {
                    this.addObject(key, obj);
                    break;
                }
            }
        }

        // Check if the object has been filled. If not, throw new Invalid Arguments Exception.
        // TODO: Should this be here? Most checks are done separately.
        if (!hasObject(key)) {
            throw new InvalidArgumentsException("Missing '" + key + "' argument!");
        }
        else {
            return this;
        }
    }


    public List<String> getArguments() {
        return args;
    }

    ////////////
    // INSTANCE METHODS
    //////////

    /**
     * Gets the original, pre-tagged arguments, as constructed. This is simply a copy of
     * the original arguments, immune from any changes that may be made (such as tag filling)
     * by the CommandExecuter.
     *
     * @return unmodified arguments from entry creation
     */
    public List<String> getOriginalArguments() {
        return pre_tagged_args;
    }

    public List<String> modifiedArguments() {
        return modified_arguments;
    }


    public String getCommandName() {
        return command;
    }

    public AbstractCommand getCommand() {
        return actualCommand;
    }


    public ScriptEntry setArguments(List<String> arguments) {
        args = arguments;
        return this;
    }


    public void setCommandName(String commandName) {
        this.command = commandName;
    }

    private ScriptEntry owner = null;

    public void setOwner(ScriptEntry owner) {
        this.owner = owner;
    }

    public ScriptEntry getOwner() {
        return owner;
    }

    private Object data;

    public Object getData() {
        return data;
    }

    public void setData(Object result) {
        this.data = result;
    }

    public void copyFrom(ScriptEntry entry) {
        entryData = entry.entryData.clone();
        setSendingQueue(entry.getResidingQueue());
    }


    //////////////////
    // SCRIPTENTRY CONTEXT
    //////////////

    public Map<String, Object> getObjects() {
        return objects;
    }


    public Object getObject(String key) {
        try {
            return objects.get(CoreUtilities.toLowerCase(key));
        }
        catch (Exception e) {
            return null;
        }
    }

    public <T extends dObject> T getdObject(String key) {
        try {
            // If an ENUM, return as an Element
            Object gotten = objects.get(CoreUtilities.toLowerCase(key));
            if (gotten instanceof Enum) {
                return (T) new Element(((Enum) gotten).name());
            }
            // Otherwise, just return the stored dObject
            return (T) gotten;
            // If not a dObject, return null
        }
        catch (Exception e) {
            return null;
        }
    }

    public Element getElement(String key) {
        try {
            return (Element) objects.get(CoreUtilities.toLowerCase(key));
        }
        catch (Exception e) {
            return null;
        }
    }


    public boolean hasObject(String key) {
        return objects.containsKey(CoreUtilities.toLowerCase(key));
    }

    /////////////
    // CORE LINKED OBJECTS
    ///////

    public dScript getScript() {
        return script;
    }


    public ScriptEntry setScript(String scriptName) {
        this.script = dScript.valueOf(scriptName);
        return this;
    }


    public ScriptQueue getResidingQueue() {
        return queue;
    }


    public void setSendingQueue(ScriptQueue scriptQueue) {
        queue = scriptQueue;
    }

    //////////////
    // TimedQueue FEATURES
    /////////


    public boolean isInstant() {
        return instant;
    }


    public ScriptEntry setInstant(boolean instant) {
        this.instant = instant;
        return this;
    }

    public boolean shouldWaitFor() {
        return waitfor;
    }


    public ScriptEntry setFinished(boolean finished) {
        waitfor = !finished;
        return this;
    }

    ////////////
    // COMPATIBILITY
    //////////

    // Keep track of objects which were added by mass
    // so that IF can inject them into new entries.
    // This is ugly, but it will keep from breaking
    // previous versions of Denizen.
    // TODO: Get rid of this
    public List<String> tracked_objects = new ArrayList<String>();

    public ScriptEntry trackObject(String key) {
        tracked_objects.add(CoreUtilities.toLowerCase(key));
        return this;
    }

    /////////////
    // DEBUGGABLE
    /////////

    public boolean fallbackDebug = true;

    @Override
    public boolean shouldDebug() {
        if (script == null || script.getContainer() == null) {
            return fallbackDebug;
        }
        return script.getContainer().shouldDebug();
    }

    @Override
    public boolean shouldFilter(String criteria) throws Exception {
        return script.getName().equalsIgnoreCase(criteria.replace("s@", ""));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String str : getOriginalArguments()) {
            sb.append(" \"" + str + "\"");
        }
        return command + sb.toString();
    }
}
