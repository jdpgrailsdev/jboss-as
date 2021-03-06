/**
 *
 */
package org.jboss.as.host.controller;

import java.util.HashMap;
import java.util.Map;

import org.jboss.as.controller.operations.common.JVMHandlers;
import org.jboss.as.controller.parsing.JvmType;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.dmr.Property;

/**
 * A Java Virtual Machine configuration.
 *
 * @author Brian Stansberry
 */
public class JvmElement {

    private static final long serialVersionUID = 4963103173530602991L;

    //Attributes
    private final String name;
    private JvmType type = JvmType.SUN;
    private String javaHome;
    private Boolean debugEnabled;
    private String debugOptions;
    private Boolean envClasspathIgnored;

    //Elements
    private String heapSize;
    private String maxHeap;
    private String permgenSize;
    private String maxPermgen;
    private String agentPath;
    private String agentLib;
    private String javaagent;
    private String stack;
    private final JvmOptionsElement jvmOptionsElement = new JvmOptionsElement();
    private Map<String, String> environmentVariables = new HashMap<String, String>();
    private Map<String, String> systemProperties = new HashMap<String, String>();

    public JvmElement(final String name) {
        this.name = name;
    }

    public JvmElement(final String name, ModelNode ... toCombine) {

        this.name = name;

        for(final ModelNode node : toCombine) {
            if(node == null) {
                continue;
            }

            if(node.hasDefined(JVMHandlers.JVM_AGENT_LIB)) {
                agentLib = node.get(JVMHandlers.JVM_AGENT_LIB).asString();
            }
            if(node.hasDefined(JVMHandlers.JVM_AGENT_PATH)) {
                agentPath = node.get(JVMHandlers.JVM_AGENT_PATH).asString();
            }
            if(node.hasDefined(JVMHandlers.JVM_DEBUG_ENABLED)) {
                debugEnabled = node.get(JVMHandlers.JVM_DEBUG_ENABLED).asBoolean();
            }
            if(node.hasDefined(JVMHandlers.JVM_DEBUG_OPTIONS)) {
                debugOptions = node.get(JVMHandlers.JVM_DEBUG_OPTIONS).asString();
            }
            if(node.hasDefined(JVMHandlers.JVM_ENV_CLASSPATH_IGNORED)) {
                envClasspathIgnored = node.get(JVMHandlers.JVM_ENV_CLASSPATH_IGNORED).asBoolean();
            }
            if(node.hasDefined(JVMHandlers.JVM_ENV_VARIABLES)) {
                for(Property property : node.get(JVMHandlers.JVM_ENV_VARIABLES).asPropertyList()) {
                    environmentVariables.put(property.getName(), property.getValue().asString());
                }
            }
            if(node.hasDefined(JVMHandlers.JVM_HEAP)) {
                final ModelNode heap = node.get(JVMHandlers.JVM_HEAP);
                if(heap.hasDefined(JVMHandlers.SIZE)) {
                    heapSize = heap.get(JVMHandlers.SIZE).asString();
                }
                if(heap.hasDefined(JVMHandlers.MAX_SIZE)) {
                    maxHeap = heap.get(JVMHandlers.MAX_SIZE).asString();
                }
            }
            if(node.hasDefined(JVMHandlers.JVM_JAVA_AGENT)) {
                javaagent = node.get(JVMHandlers.JVM_JAVA_AGENT).asString();
            }
            if(node.hasDefined(JVMHandlers.JVM_JAVA_HOME)) {
                javaHome = node.get(JVMHandlers.JVM_JAVA_HOME).asString();
            }
            if(node.hasDefined(JVMHandlers.JVM_OPTIONS)) {
                for(final ModelNode option : node.get(JVMHandlers.JVM_OPTIONS).asList()) {
                    jvmOptionsElement.addOption(option.asString());
                }
            }
            if(node.hasDefined(JVMHandlers.JVM_PERMGEN)) {
                final ModelNode permGen = node.get(JVMHandlers.JVM_PERMGEN);
                if(permGen.hasDefined(JVMHandlers.SIZE)) {
                    permgenSize = permGen.get(JVMHandlers.SIZE).asString();
                }
                if(permGen.hasDefined(JVMHandlers.MAX_SIZE)) {
                    maxPermgen = permGen.get(JVMHandlers.MAX_SIZE).asString();
                }
            }
            if(node.hasDefined(JVMHandlers.JVM_STACK)) {
                stack = node.get(JVMHandlers.JVM_STACK).asString();
            }
            if(node.hasDefined(JVMHandlers.JVM_SYSTEM_PROPERTIES)) {
                for(Property property : node.get(JVMHandlers.JVM_SYSTEM_PROPERTIES).asPropertyList()) {
                    final ModelNode value = property.getValue();
                    systemProperties.put(property.getName(), isDefined(value) ? value.asString() : "true");
                }
            }
        }

    }

    public String getJavaHome() {
        return javaHome;
    }

    void setJavaHome(String javaHome) {
        this.javaHome = javaHome;
    }

    public JvmType getJvmType() {
        return type;
    }

    void setJvmType(JvmType type) {
        this.type = type;
    }

    public String getPermgenSize() {
        return permgenSize;
    }

    void setPermgenSize(String permgenSize) {
        this.permgenSize = permgenSize;
    }

    public String getMaxPermgen() {
        return maxPermgen;
    }

    void setMaxPermgen(String maxPermgen) {
        this.maxPermgen = maxPermgen;
    }

    public String getHeapSize() {
        return heapSize;
    }

    void setHeapSize(String heapSize) {
        this.heapSize = heapSize;
    }

    public String getMaxHeap() {
        return maxHeap;
    }

    void setMaxHeap(String maxHeap) {
        this.maxHeap = maxHeap;
    }

    public String getName() {
        return name;
    }

    public Boolean isDebugEnabled() {
        return debugEnabled;
    }

    void setDebugEnabled(Boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    public String getDebugOptions() {
        return debugOptions;
    }

    void setDebugOptions(String debugOptions) {
        this.debugOptions = debugOptions;
    }

    public String getStack() {
        return stack;
    }

    void setStack(String stack) {
        this.stack = stack;
    }

    public Boolean isEnvClasspathIgnored() {
        return envClasspathIgnored;
    }

    void setEnvClasspathIgnored(Boolean envClasspathIgnored) {
        this.envClasspathIgnored = envClasspathIgnored;
    }

    public JvmOptionsElement getJvmOptions() {
        return jvmOptionsElement;
    }

    public Map<String, String> getEnvironmentVariables() {
        return environmentVariables;
    }

    public Map<String, String> getSystemProperties() {
        return systemProperties;
    }

    public String getAgentPath() {
        return agentPath;
    }

    void setAgentPath(String agentPath) {
        if (agentLib != null) {
            throw new IllegalArgumentException("Attempting to set 'agent-path' when 'agent-lib' was already set");
        }
        this.agentPath = agentPath;
    }

    public String getAgentLib() {
        return agentLib;
    }

    void setAgentLib(String agentLib) {
        if (agentPath != null) {
            throw new IllegalArgumentException("Attempting to set 'agent-lib' when 'agent-path' was already set");
        }
        this.agentLib = agentLib;
    }

    public String getJavaagent() {
        return javaagent;
    }

    void setJavaagent(String javaagent) {
        this.javaagent = javaagent;
    }

    static boolean isDefined(final ModelNode node) {
        return node.getType() != ModelType.UNDEFINED;
    }

}
