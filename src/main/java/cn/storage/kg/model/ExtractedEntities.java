package cn.storage.kg.model;

import java.util.ArrayList;
import java.util.List;

public class ExtractedEntities {
    private List<String> countries = new ArrayList<>();
    private List<String> organizations = new ArrayList<>();
    private List<String> persons = new ArrayList<>();
    private List<String> locations = new ArrayList<>();

    public List<String> getCountries() { return countries; }
    public void setCountries(List<String> countries) { this.countries = countries; }
    public List<String> getOrganizations() { return organizations; }
    public void setOrganizations(List<String> organizations) { this.organizations = organizations; }
    public List<String> getPersons() { return persons; }
    public void setPersons(List<String> persons) { this.persons = persons; }
    public List<String> getLocations() { return locations; }
    public void setLocations(List<String> locations) { this.locations = locations; }
}
