package com.bgrc.attendance.domain.user.repository;

import com.bgrc.attendance.domain.user.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Slf4j
public class UserRepository {
    /**
     * 새 명단은 완전히 파싱한 뒤 한 번에 교체한다. QR 조회 중에는 이전 또는 새 명단 중
     * 하나만 보이도록 volatile 참조를 사용하고, 업로드한 Excel의 행 순서도 보존한다.
     */
    private volatile Map<String, User> userMap = Map.of();

    private String generateKey(String name, String birthDate){
        return name + ":" + birthDate;
    }

    private String generateKey(User user){
        return user.getName() + ":" + user.getBirthDate();
    }

    public Boolean findByNameAndBirthDate(String name, String birthDate){
        return userMap.containsKey(generateKey(name, birthDate));
    }

    public Optional<User> findUser(String name, String birthDate) {
        return Optional.ofNullable(userMap.get(generateKey(name, birthDate)));
    }

    // JPA 느낌내도록 네이밍
    public synchronized void save(User user){
        Map<String, User> replacement = new LinkedHashMap<>(userMap);
        replacement.put(generateKey(user.getName(), user.getBirthDate()), user);
        userMap = Collections.unmodifiableMap(replacement);
    }

    public int count(){
        return userMap.size();
    }

    public synchronized void clear(){
        userMap = Map.of();
    }

    public synchronized void replaceAll(List<User> users) {
        Map<String, User> replacement = new LinkedHashMap<>();
        for (User user : users) {
            replacement.put(generateKey(user), user);
        }
        userMap = Collections.unmodifiableMap(replacement);
    }

    public List<User> findAll() {
        return List.copyOf(userMap.values());
    }

}
