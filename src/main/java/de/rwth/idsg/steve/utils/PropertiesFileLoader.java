package de.rwth.idsg.steve.utils;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Encapsulates java.util.Properties and adds type specific convenience methods
 *
 * @author Sevket Goekay <goekay@dbis.rwth-aachen.de>
 * @since 01.10.2015
 */
@Slf4j
public class PropertiesFileLoader {

    private Properties prop;

    public PropertiesFileLoader(String fileName) {
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(fileName)) {
            if (is == null) {
                throw new FileNotFoundException("Property file '" + fileName + "' is not found in classpath");
            }
            prop = new Properties();
            prop.load(is);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Strict
    // -------------------------------------------------------------------------

    public String getString(String key) {
        String s = prop.getProperty(key);

        if (s == null) {
            throw new IllegalArgumentException("The property '" + key + "' is not found");
        }

        if (s.isEmpty()) {
            throw new IllegalArgumentException("The property '" + key + "' has no value set");
        }

        return trim(key, s);
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getString(key));
    }

    public int getInt(String key) {
        return Integer.parseInt(getString(key));
    }

    // -------------------------------------------------------------------------
    // Return null if not set
    // -------------------------------------------------------------------------

    public String getOptionalString(String key) {
        String s = prop.getProperty(key);
        if (Strings.isNullOrEmpty(s)) {
            return null;
        }
        return trim(key, s);
    }

    public Boolean getOptionalBoolean(String key) {
        String s = getOptionalString(key);
        if (s == null) {
            // In this special case, to make findbugs happy, we don't return null.
            // Reason: http://findbugs.sourceforge.net/bugDescriptions.html#NP_BOOLEAN_RETURN_NULL
            return false;
        } else {
            return Boolean.parseBoolean(s);
        }
    }

    public Integer getOptionalInt(String key) {
        String s = getOptionalString(key);
        if (s == null) {
            return null;
        } else {
            return Integer.parseInt(s);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String trim(String key, String value) {
        String trimmed = value.trim();
        if (!trimmed.equals(value)) {
            log.warn("The property '{}' has leading or trailing spaces which were removed!", key);
        }
        return trimmed;
    }
}
