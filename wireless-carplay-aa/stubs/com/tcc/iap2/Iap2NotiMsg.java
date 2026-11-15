package com.tcc.iap2;

import java.util.ArrayList;
import java.util.List;

/** An iAP2 message: a group of items (id, type, values). As on the car, a getter of the wrong type gives null. */
public class Iap2NotiMsg {
    final Iap2ObjGroup group = new Iap2ObjGroup();

    public Iap2ObjGroup getItemGroup() {
        return group;
    }

    public class Iap2ObjGroup {
        final List<Iap2ObjItem> items = new ArrayList<Iap2ObjItem>();

        public int getItemCnt() {
            return items.size();
        }

        public Iap2ObjItem getObjItem(int index) {
            return items.get(index);
        }

        /** off-car tests: add an item (values: int[], long[], String[], byte[] or Iap2ObjGroup[]) */
        public Iap2ObjGroup add(int id, int type, Object values) {
            items.add(new Iap2ObjItem(id, type, values, false));
            return this;
        }

        /** Native Parcel construction differs from the public array constructor:
         * zero-count typed arrays have null backing data on the measured unit. */
        public Iap2ObjGroup addFromParcel(int id, int type, Object values) {
            items.add(new Iap2ObjItem(id, type, values, true));
            return this;
        }
    }

    public class Iap2ObjItem {
        final int id, type;
        final Object values;
        final boolean fromParcel;

        Iap2ObjItem(int id, int type, Object values, boolean fromParcel) {
            this.id = id;
            this.type = type;
            this.values = values;
            this.fromParcel = fromParcel;
        }

        public int getItemId() {
            return id;
        }

        public int getItemParamType() {
            return type;
        }

        public int getItemObjCount() {
            return values == null ? 0 : java.lang.reflect.Array.getLength(values);
        }

        public int[] getItemIntArray() {
            if (fromParcel && getItemObjCount() == 0 && (type == 1 || type == 5 || type == 6 || type == 8 || type == 9))
                throw new NullPointerException("vendor empty Parcel integer backing array");
            return values instanceof int[] ? (int[]) values : null;
        }

        public long[] getItemLongArray() {
            return values instanceof long[] ? (long[]) values : null;
        }

        public String[] getItemStringArray() {
            return values instanceof String[] ? (String[]) values : null;
        }

        public byte[] getItemByteArray() {
            return values instanceof byte[] ? (byte[]) values : null;
        }

        public Iap2ObjGroup[] getItemGroupArray() {
            if (fromParcel && type == 13 && getItemObjCount() == 0)
                throw new NullPointerException("vendor empty Parcel group backing array");
            if (!(values instanceof Iap2ObjGroup[])) return null;
            // Vendor getter serializes and reparses each child before returning it.
            Iap2ObjGroup[] original = (Iap2ObjGroup[]) values, copy = new Iap2ObjGroup[original.length];
            for (int k = 0; k < original.length; k++) {
                if (original[k] == null) continue;
                copy[k] = new Iap2ObjGroup();
                for (Iap2ObjItem it : original[k].items) copy[k].addFromParcel(it.id, it.type, it.values);
            }
            return copy;
        }
    }
}
