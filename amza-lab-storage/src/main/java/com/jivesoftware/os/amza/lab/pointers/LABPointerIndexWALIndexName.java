package com.jivesoftware.os.amza.lab.pointers;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author jonathan.colt
 */
public class LABPointerIndexWALIndexName {

    public enum Type {

        active,
        compacting,
        compacted,
        backup;
    }

    private final int modulo;
    private final Type type;
    private final String name;

    public LABPointerIndexWALIndexName(int modulo, Type type, String name) {
        this.modulo = modulo;
        this.type = type;
        this.name = name;
    }

    public String getPrimaryName() {
        return modulo + "/primary-" + type.toString() + "-" + name;
    }

    public String getPrefixName() {
        return modulo + "/prefix-" + type.toString() + "-" + name;
    }

    public LABPointerIndexWALIndexName typeName(Type type) {
        return new LABPointerIndexWALIndexName(modulo, type, name);
    }

    public List<LABPointerIndexWALIndexName> all() {
        List<LABPointerIndexWALIndexName> all = new ArrayList<>();
        for (Type v : Type.values()) {
            all.add(typeName(v));
        }
        return all;
    }

    @Override
    public String toString() {
        return "LABPointerIndexWALIndexName{" + "type=" + type + ", name=" + name + '}';
    }
}
