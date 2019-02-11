// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.configserver.noderepository.reports;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Optional;

import static com.yahoo.yolean.Exceptions.uncheck;

/**
 * The most basic form of a node repository report on a node.
 *
 * <p>This class can be used directly for simple reports, or can be used as a base class for richer reports.
 *
 * <p><strong>Subclass requirements</strong>
 *
 * <ol>
 *     <li>A subclass mush be a Jackson class that can be mapped to {@link JsonNode} with {@link #toJsonNode()},
 *     and from {@link JsonNode} with {@link #fromJsonNode(JsonNode, Class)}.</li>
 *     <li>A subclass must override {@link #updates(BaseReport)} and make sure to return false if
 *     {@code !super.updates(current)}.</li>
 * </ol>
 *
 * @author hakonhall
 */
// @Immutable
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BaseReport {
    /** The time the report was created, in milliseconds since Epoch. */
    public static final String CREATED_FIELD = "createdMillis";
    /** The description of the error (implies wanting to fail out node). */
    public static final String DESCRIPTION_FIELD = "description";

    protected static final ObjectMapper mapper = new ObjectMapper();

    private final Long createdMillis;
    private final String description;

    @JsonCreator
    public BaseReport(@JsonProperty(CREATED_FIELD) Long createdMillisOrNull,
                      @JsonProperty(DESCRIPTION_FIELD) String descriptionOrNull) {
        this.createdMillis = createdMillisOrNull;
        this.description = descriptionOrNull;

    }

    @JsonGetter(CREATED_FIELD)
    public final Long getCreatedMillisOrNull() {
        return createdMillis;
    }

    @JsonGetter(DESCRIPTION_FIELD)
    public final String getDescriptionOrNull() {
        return description;
    }

    /**
     * Assume {@code this} is a freshly made report, and {@code current} is the report in the node repository:
     * Return true iff the node repository should be updated.
     *
     * <p>The createdMillis field is ignored in this method (unless it is earlier than {@code current}'s?).
     */
    public boolean updates(BaseReport current) {
        if (this == current) return false;
        if (this.getClass() != current.getClass()) return true;
        return !Objects.equals(description, current.description);
    }

    /** A variant of {@link #updates(BaseReport)} handling possibly absent reports, whether new or old. */
    public static <TNEW extends BaseReport, TOLD extends BaseReport>
    boolean updates2(Optional<TNEW> newReport, Optional<TOLD> oldReport) {
        if (newReport.isPresent() ^ oldReport.isPresent()) return true;
        return newReport.map(r -> r.updates(oldReport.get())).orElse(false);
    }

    public static BaseReport fromJsonNode(JsonNode jsonNode) {
        return fromJsonNode(jsonNode, BaseReport.class);
    }

    public static <R extends BaseReport> R fromJsonNode(JsonNode jsonNode, Class<R> jacksonClass) {
        return uncheck(() -> mapper.treeToValue(jsonNode, jacksonClass));
    }

    public static BaseReport fromJson(String json) {
        return fromJson(json, BaseReport.class);
    }

    public static <R extends BaseReport> R fromJson(String json, Class<R> jacksonClass) {
        return uncheck(() -> mapper.readValue(json, jacksonClass));
    }

    /** Returns {@code this} as a {@link JsonNode}. */
    public JsonNode toJsonNode() {
        return uncheck(() -> mapper.valueToTree(this));
    }

    /** Returns {@code this} as a compact JSON string. */
    public String toJson() {
        return uncheck(() -> mapper.writeValueAsString(this));
    }
}
