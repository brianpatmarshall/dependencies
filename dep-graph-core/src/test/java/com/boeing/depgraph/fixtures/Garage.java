package com.boeing.depgraph.fixtures;

import java.util.ArrayList;
import java.util.List;

/**
 * The busy one. Carries at least one of every relation the scanner can detect.
 *
 * <p>Deliberately written the way ordinary code is written rather than as a checklist:
 * every relation below is a side effect of doing something plausible, which is the only
 * way to be sure the detection works on real bytecode.
 */
@Audited
public class Garage {

    /** FIELD_TYPE -> Toolbox */
    private final Toolbox toolbox;

    /** FIELD_TYPE -> List, TYPE_ARGUMENT -> Part (Part survives only in the signature) */
    private final List<Part> stock = new ArrayList<>();

    /** PARAM -> Toolbox, on a constructor. Bytecode calls it a method named <init>. */
    public Garage(Toolbox toolbox) {
        this.toolbox = toolbox;
    }

    /** PARAM -> Car, RETURNS -> Invoice, INSTANTIATES -> Invoice */
    public Invoice service(Car car) {
        car.drive();                       // CALLS -> Car
        return new Invoice();              // INSTANTIATES -> Invoice
    }

    /** PARAM -> Vehicle, CASTS_TO -> Car */
    public void park(Vehicle vehicle) {
        if (vehicle instanceof Car) {      // CASTS_TO -> Car
            Car car = (Car) vehicle;       // CASTS_TO -> Car
            car.registration();            // CALLS -> Car
        }
    }

    /** THROWS -> InspectionFailed, CATCHES -> InspectionFailed */
    public void inspect(Car car) throws InspectionFailed {
        try {
            check(car);
        } catch (InspectionFailed e) {
            throw new InspectionFailed("re-inspection needed: " + e.getMessage());
        }
    }

    private void check(Car car) throws InspectionFailed {
        if (car == null) {
            throw new InspectionFailed("no car");
        }
    }

    /** READS_FIELD and WRITES_FIELD -> Toolbox */
    public void restock() {
        int current = toolbox.wrenchCount;  // READS_FIELD -> Toolbox
        toolbox.wrenchCount = current + 1;  // WRITES_FIELD -> Toolbox
    }

    /** RETURNS -> List, TYPE_ARGUMENT -> Part, CALLS -> Part via the method reference */
    public List<String> partNames() {
        return stock.stream().map(Part::name).toList();
    }

    /** REFERENCES -> Shape, via a class literal */
    public Class<?> shapeType() {
        return Shape.class;
    }

    /**
     * Uses MAX_BAYS and produces no edge to Limits, because javac inlines the literal.
     * DEFAULT_BAY is not a compile-time constant, so that reference does survive.
     */
    public String describe() {
        return "bays=" + Limits.MAX_BAYS + " default=" + Limits.DEFAULT_BAY;
    }

    /** NESTED_IN -> Garage */
    public static class Bay {

        private final int number;

        public Bay(int number) {
            this.number = number;
        }

        public int number() {
            return number;
        }
    }
}
