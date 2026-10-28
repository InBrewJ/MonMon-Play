package helpers;

import org.postgresql.util.PGobject;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

@Converter
public class JsonbConverter implements AttributeConverter<String, Object> {

    @Override
    public Object convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            PGobject pgo = new PGobject();
            pgo.setType("jsonb");
            pgo.setValue(attribute);
            return pgo;
        } catch (Exception e) {
            return attribute;
        }
    }

    @Override
    public String convertToEntityAttribute(Object dbData) {
        if (dbData == null) {
            return null;
        }
        if (dbData instanceof PGobject) {
            return ((PGobject) dbData).getValue();
        }
        return dbData.toString();
    }
}
