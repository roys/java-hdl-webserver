package com.roysolberg.java.hdlserver.hdl.component;

import java.io.Serializable;
import java.util.Date;

public class SmsComponent extends HdlComponent {

    public SmsComponent(int subnet, int deviceId, int deviceType, String remark) {
        super(subnet, deviceId, deviceType, remark);
    }

    @Override
    public String getDescription() {
        return "SMS module";
    }

    @Override
    public String getDeviceGroup() {
        return "messsage";
    }

    @Override
    public int getDefaultSortOrder() {
        return 999;
    }

    public static class Sms implements Serializable, Comparable<Sms> {

        protected int index; // Indx/ID of SMS at SMS module
        protected boolean incoming; // Incoming or outgoing SMS
        protected String phoneNumber;
        protected Date date;
        protected String message;

        public Sms(int index, boolean incoming, String phoneNumber, Date date, String message) {
            this.index = index;
            this.incoming = incoming;
            this.phoneNumber = phoneNumber;
            this.date = date;
            this.message = message;
        }

        public int getIndex() {
            return index;
        }

        public Date getDate() {
            return date;
        }

        public boolean isIncoming() {
            return incoming;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public boolean equals(Object o) {
            if (o != null && o instanceof Sms) {
                Sms other = (Sms) o;
                return index == other.index && incoming == other.incoming && phoneNumber.equals(other.phoneNumber) && (date != null && other.date != null && date.equals(other.date)) && message.equals(other.message);
            }
            return false;
        }

        @Override
        public String toString() {
            return String.format("Sms(index:[%s],incoming:[%s],phoneNumber:[%s],date:[%s],message:[%s]", index, incoming, phoneNumber, date, message);
        }

        @Override
        public int hashCode() {
            int result = index;
            result = 31 * result + (incoming ? 1 : 0);
            result = 31 * result + phoneNumber.hashCode();
            if (date != null) {
                result = 31 * result + date.hashCode();
            }
            result = 31 * result + message.hashCode();
            return result;
        }

        @Override
        public int compareTo(Sms another) {
            if (date == null && another.date != null) {
                return 1;
            }
            if (date != null && another.date == null) {
                return -1;
            }
            if (date == null) { // Both dates null
                return index < another.index ? -1 : (another.index < index ? 1 : 0);
            }
            int result = another.date.compareTo(date);
            if (result == 0) {
                return index < another.index ? -1 : (another.index < index ? 1 : 0);
            }
            return result;
        }
    }

}
