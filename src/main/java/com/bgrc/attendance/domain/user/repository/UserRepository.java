package com.bgrc.attendance.domain.user.repository;

import com.bgrc.attendance.domain.user.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Slf4j
public class UserRepository {
    private Map<String, User> userMap = new ConcurrentHashMap<>(); // 순서 보장 안됨

    private String generateKey(String name, String birthDate){
        return name + ":" + birthDate;
    }

    private String generateKey(User user){
        return user.getName() + ":" + user.getBirthDate();
    }

    public Boolean findByNameAndBirthDate(String name, String birthDate){
        return userMap.containsKey(generateKey(name, birthDate));
    }

    // JPA 느낌내도록 네이밍
    public void save(User user){
        userMap.put(generateKey(user.getName(), user.getBirthDate()), user);
    }

    public int count(){
        return userMap.size();
    }

    public void clear(){
        userMap.clear();
    }

}
