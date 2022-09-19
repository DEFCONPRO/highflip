package com.baidu.highflip.server.respository;

import com.baidu.highflip.core.entity.runtime.Party;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PartyRepository extends JpaRepository<Party, String> {
}
