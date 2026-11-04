<script setup lang="ts">
import ConnectorDetails from "./components/ConnectorDetails.vue";
import { SLOTS } from "@kestra-io/artifact-sdk";
</script>

<template>
    <div style="padding: 1rem; font-family: sans-serif">
        <ConnectorDetails v-bind="SLOTS['execution-artifact'].defaultProps" />
    </div>
</template>