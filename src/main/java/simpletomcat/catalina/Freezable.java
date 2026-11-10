package simpletomcat.catalina;

/** 시작 전에 구성을 동결하는 계약. */
public interface Freezable {
    void freeze();

    boolean isFrozen();
}
