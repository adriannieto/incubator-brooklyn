package brooklyn.location.basic.jclouds.pool;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jclouds.compute.options.TemplateOptions;

import brooklyn.location.basic.jclouds.templates.PortableTemplateBuilder;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * A facility for having a template we can declare without knowing the provider,
 * then find matching instances, create instances, and generally manipulate them.
 * <p>
 * NB: to be sure of matching a specific template, you should provide a unique id in the constructor.
 * (this will force 'strict' mode.)
 */
// TODO we could use a hashcode over the values of template-builder and template-options fields, as a tag/usermetadata, 
// to guarantee (virtually) matching only machines created from this template (instead of asking for unique id)
public class ReusableMachineTemplate extends PortableTemplateBuilder<ReusableMachineTemplate> {

    public static final String PREFIX = "brooklyn:template.";
    public static final String NAME_METADATA_KEY = PREFIX+"name";
    public static final String DESCRIPTION_METADATA_KEY = PREFIX+"name";
    public static final String HASH_METADATA_KEY = PREFIX+"hash";
    public static final String TEMPLATE_OWNER_METADATA_KEY = PREFIX+"owner";
    
    private String name = null;
    private String templateOwner = null;
    private String description = null;
    private boolean strict;
    
    public ReusableMachineTemplate() { strict = false; }
    public ReusableMachineTemplate(String name) { name(name); }
    
    /** see #getName() */
    public ReusableMachineTemplate name(String name) {
        this.name = name;
        strict = true;
        return this;
    }
    
    /** see #getDescription() */
    public ReusableMachineTemplate description(String description) {
        this.description = description;
        return this;
    }

    /** whether this template only matches machines instances created from this template; 
     * defaults true if a name is set, otherwise false.
     * if false, it will ignore name, owner, and hashcode */
    public ReusableMachineTemplate strict(boolean strict) {
        this.strict = strict;
        return this;
    }

    /** no owner, means anyone can pick this up (default) */
    public ReusableMachineTemplate templateUnowned() {
        return templateOwner(null);
    }
    /** adds user.name as owner of this template */
    public ReusableMachineTemplate templateOwnedByMe() {
        return templateOwner(System.getProperty("user.name"));
    }
    /** adds an owner tag to this template */
    public ReusableMachineTemplate templateOwner(String owner) {
        this.templateOwner = owner;
        return this;
    }
    
    /** human-friendly name for this template. should normally be unique, it is the primary differentiator for strict matching. */
    public String getName() {
        return name;
    }
    
    /** a description for this template; this is set on created machines but _not_ used to filter them 
     * (so you can change description freely).  */
    public String getDescription() {
        return description;
    }
    
    public String getOwner() {
        return templateOwner;
    }
    
    public boolean isStrict() {
        return strict;
    }

    @Override
    public List<TemplateOptions> getAdditionalOptions() {
        List<TemplateOptions> result = new ArrayList<TemplateOptions>();
        result.addAll(super.getAdditionalOptions());
        if (isStrict()) addStrictOptions(result);
        return result;
    }

    @Override
    public List<TemplateOptions> getAdditionalOptionalOptions() {
        List<TemplateOptions> result = new ArrayList<TemplateOptions>();
        result.addAll(super.getAdditionalOptions());
        addStrictOptions(result);
        return result;
    }
    
    protected void addStrictOptions(List<TemplateOptions> result) {
        if (name!=null) result.add(TemplateOptions.Builder.userMetadata(NAME_METADATA_KEY, name));
        if (templateOwner!=null) result.add(TemplateOptions.Builder.userMetadata(TEMPLATE_OWNER_METADATA_KEY, templateOwner));
        result.add(TemplateOptions.Builder.userMetadata(HASH_METADATA_KEY, ""+hashCode()));
    }
    
    /** computes the user metadata that this template will set (argument true) or required to match (argument false) */
    public Map<String,String> getUserMetadata(boolean includeOptional) {
        return ImmutableMap.copyOf(computeAggregatedOptions(includeOptional).getUserMetadata());
    }

    /** computes the tags that this template will set (argument true) or require to match (argument false) */
    public Set<String> getTags(boolean includeOptional) {
        return ImmutableSet.copyOf(computeAggregatedOptions(includeOptional).getTags());
    }
    
    public ReusableMachineTemplate tag(String tag) {
        return tags(tag);
    }
    public ReusableMachineTemplate tags(String ...tags) {
        return addOptions(TemplateOptions.Builder.tags(Arrays.asList(tags)));
    }

    public ReusableMachineTemplate metadata(String key, String value) {
        return addOptions(TemplateOptions.Builder.userMetadata(key, value));
    }
    public ReusableMachineTemplate metadata(Map m) {
        return addOptions(TemplateOptions.Builder.userMetadata(m));
    }

    public ReusableMachineTemplate tagOptional(String tag) {
        return tagsOptional(tag);
    }
    public ReusableMachineTemplate tagsOptional(String ...tags) {
        return addOptionalOptions(TemplateOptions.Builder.tags(Arrays.asList(tags)));
    }

    public ReusableMachineTemplate metadataOptional(String key, String value) {
        return addOptionalOptions(TemplateOptions.Builder.userMetadata(key, value));
    }
    public ReusableMachineTemplate metadataOptional(Map m) {
        return addOptionalOptions(TemplateOptions.Builder.userMetadata(m));
    }

    @Override
    public String toString() {
        String s = makeNonTrivialArgumentsString();
        return (name!=null ? name : "Template") + " [" +(s!=null && s.length()>0 ? ", "+s : "") + "]";
    }
    
}