package com.krishcpatel.realm.jobs.model;

/**
 * Result for joining or leaving a job.
 *
 * @param success true if the operation completed
 * @param message status message
 */
public record JobMembershipResult(boolean success, String message) {
    /**
     * Creates a successful membership result.
     *
     * @param message success message
     * @return success result
     */
    public static JobMembershipResult ok(String message) {
        return new JobMembershipResult(true, message);
    }

    /**
     * Creates a failed membership result.
     *
     * @param message failure message
     * @return failure result
     */
    public static JobMembershipResult fail(String message) {
        return new JobMembershipResult(false, message);
    }
}
