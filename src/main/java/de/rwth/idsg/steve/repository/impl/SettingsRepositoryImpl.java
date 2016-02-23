package de.rwth.idsg.steve.repository.impl;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import de.rwth.idsg.steve.NotificationFeature;
import de.rwth.idsg.steve.SteveException;
import de.rwth.idsg.steve.repository.SettingsRepository;
import de.rwth.idsg.steve.repository.dto.MailSettings;
import de.rwth.idsg.steve.web.dto.SettingsForm;
import jooq.steve.db.tables.records.SettingsRecord;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static jooq.steve.db.tables.Settings.SETTINGS;

/**
 * @author Sevket Goekay <goekay@dbis.rwth-aachen.de>
 * @since 06.11.2015
 */
@Repository
public class SettingsRepositoryImpl implements SettingsRepository {

    private static final String APP_ID = new String(Base64.getEncoder().encode("SteckdosenVerwaltung".getBytes()));

    private static final Splitter SPLITTER = Splitter.on(",").trimResults().omitEmptyStrings();
    private static final Joiner JOINER = Joiner.on(",").skipNulls();

    @Autowired private DSLContext ctx;

    @Override
    public SettingsForm getForm() {
        SettingsRecord r = getInternal();

        List<String> eMails = split(r.getMailRecipients());
        List<NotificationFeature> features = splitFeatures(r.getNotificationFeatures());

        return SettingsForm.builder()
                           .heartbeat(toMin(r.getHeartbeatIntervalInSeconds()))
                           .expiration(r.getHoursToExpire())
                           .enabled(r.getMailEnabled())
                           .host(r.getMailHost())
                           .username(r.getMailUsername())
                           .password(r.getMailPassword())
                           .from(r.getMailFrom())
                           .protocol(r.getMailProtocol())
                           .port(r.getMailPort())
                           .recipients(eMails)
                           .enabledFeatures(features)
                           .build();

    }

    @Override
    public MailSettings getMailSettings() {
        SettingsRecord r = getInternal();

        List<String> eMails = split(r.getMailRecipients());
        List<NotificationFeature> features = splitFeatures(r.getNotificationFeatures());

        return MailSettings.builder()
                           .enabled(r.getMailEnabled())
                           .host(r.getMailHost())
                           .username(r.getMailUsername())
                           .password(r.getMailPassword())
                           .from(r.getMailFrom())
                           .protocol(r.getMailProtocol())
                           .port(r.getMailPort())
                           .recipients(eMails)
                           .enabledFeatures(features)
                           .build();
    }

    @Override
    public int getHeartbeatIntervalInSeconds() {
        return getInternal().getHeartbeatIntervalInSeconds();
    }

    @Override
    public int getHoursToExpire() {
        return getInternal().getHoursToExpire();
    }

    @Override
    public void update(SettingsForm form) {
        String eMails = join(form.getRecipients());
        String features = join(form.getEnabledFeatures());

        try {
            ctx.update(SETTINGS)
               .set(SETTINGS.HEARTBEAT_INTERVAL_IN_SECONDS, toSec(form.getHeartbeat()))
               .set(SETTINGS.HOURS_TO_EXPIRE, form.getExpiration())
               .set(SETTINGS.MAIL_ENABLED, form.getEnabled())
               .set(SETTINGS.MAIL_HOST, form.getHost())
               .set(SETTINGS.MAIL_USERNAME, form.getUsername())
               .set(SETTINGS.MAIL_PASSWORD, form.getPassword())
               .set(SETTINGS.MAIL_FROM, form.getFrom())
               .set(SETTINGS.MAIL_PROTOCOL, form.getProtocol())
               .set(SETTINGS.MAIL_PORT, form.getPort())
               .set(SETTINGS.MAIL_RECIPIENTS, eMails)
               .set(SETTINGS.NOTIFICATION_FEATURES, features)
               .where(SETTINGS.APP_ID.eq(APP_ID))
               .execute();

        } catch (DataAccessException e) {
            throw new SteveException("FAILED to save the settings", e);
        }
    }

    private SettingsRecord getInternal() {
        return ctx.selectFrom(SETTINGS)
                  .where(SETTINGS.APP_ID.eq(APP_ID))
                  .fetchOne();
    }

    private static int toMin(int seconds) {
        return (int) TimeUnit.SECONDS.toMinutes(seconds);
    }

    private static int toSec(int minutes) {
        return (int) TimeUnit.MINUTES.toSeconds(minutes);
    }

    private String join(Collection<?> col) {
        if (col == null || col.isEmpty()) {
            return null;
        } else {
            // Use HashSet to trim duplicates before inserting into DB
            return JOINER.join(new HashSet<>(col));
        }
    }

    private List<String> split(String str) {
        if (str == null || str.isEmpty()) {
            return Collections.emptyList();
        } else {
            return SPLITTER.splitToList(str);
        }
    }

    private List<NotificationFeature> splitFeatures(String str) {
        return split(str).stream()
                         .map(NotificationFeature::fromName)
                         .collect(Collectors.toList());
    }
}
