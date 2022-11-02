package com.baidu.highflip.core.entity.dag;

import com.baidu.highflip.core.entity.dag.codec.AttributeMap;
import com.baidu.highflip.core.entity.dag.common.AttributeObject;
import highflip.HighflipMeta;
import lombok.Data;
import org.hibernate.cfg.NotYetImplementedException;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
public class Party extends AttributeObject implements Serializable {

    private static final long serialVersionUID = 0x85710003L;

    String name;

    String description;

    Map<String, Object> attributes;

    String role;

    List<PartyNode> nodes;

    public static Party fromProto(HighflipMeta.PartyProto proto) {
        Party p = new Party();
        p.setName(proto.getName());
        p.setAttributes(AttributeMap.fromProto(proto.getAttributesMap()));
        p.setNodes(proto.getNodesList()
                .stream()
                .map(PartyNode::fromProto)
                .map(n -> {
                    n.setParent(p);
                    return n;
                })
                .collect(Collectors.toList()));
        return p;
    }

    public static HighflipMeta.PartyProto toProto(Party party) {
        throw new NotYetImplementedException();
    }
}
